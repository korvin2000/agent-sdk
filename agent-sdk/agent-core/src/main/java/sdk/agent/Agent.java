package sdk.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.AgentListener;
import sdk.agent.event.EventSink;
import sdk.agent.event.ListenerFanout;
import sdk.agent.event.QueueSink;
import sdk.agent.event.RunOutcome;
import sdk.agent.event.Subscription;
import sdk.agent.hook.AgentHooks;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AgentMessageCodec;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.MessageConverter;
import sdk.agent.message.StopReason;
import sdk.agent.message.UserMessage;
import sdk.agent.prompt.PromptContext;
import sdk.agent.prompt.SectionSpec;
import sdk.agent.prompt.SystemPromptBuilder;
import sdk.agent.prompt.SystemPromptOverride;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.spi.Extension;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.turn.TurnMachine;

/// The facade. Owns at most one live run, the two message queues, the listener fan-out and the
/// transcript between runs. Four deliberate divergences from pi-mono: `abort()` clears both
/// queues; `reset()` aborts and waits first; listener throws are isolated; late events never throw.
public final class Agent implements AutoCloseable {

    /// Everything the builder decided, immutable.
    record Config(LlmProvider provider,
                  ToolRegistry tools,
                  AgentHooks hooks,
                  MessageConverter converter,
                  Map<String, AgentMessageCodec> codecs,
                  List<SectionSpec> sections,
                  PromptContext promptContext,
                  Optional<SystemPromptOverride> promptOverride,
                  LlmRequest requestTemplate,
                  RunLimits limits,
                  Clock clock,
                  Duration toolIdleTimeout,
                  List<Extension> extensions) { }

    private static final System.Logger LOG = System.getLogger(Agent.class.getName());

    private final Config cfg;
    private final ListenerFanout fanout;
    private final MessageQueue steering = new MessageQueue();
    private final MessageQueue followUps = new MessageQueue();
    private final List<QueueSink> agentQueues = new java.util.ArrayList<>();
    // A per-task virtual-thread executor rather than a raw Thread: the driver binds RunScope itself
    // on every advance(), so nothing here depends on ScopedValue inheritance (C10).
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
    private final Object lock = new Object();

    private List<AgentMessage> transcript = List.of();      // guarded by lock
    private AgentRun active;                                // guarded by lock
    private AgentRun last;                                  // guarded by lock
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);   // guarded by lock

    Agent(Config cfg, ListenerFanout fanout) {
        this.cfg = Objects.requireNonNull(cfg);
        this.fanout = Objects.requireNonNull(fanout);
    }

    public static AgentBuilder builder() { return AgentBuilder.create(); }

    // ---- running -------------------------------------------------------------------------------

    /// @throws IllegalStateException if a run is active
    public AgentRun prompt(List<AgentMessage> messages) { return start(List.copyOf(messages), false); }

    public AgentRun prompt(String text) { return prompt(List.of(UserMessage.text(text, cfg.clock().instant()))); }

    public AgentRun prompt(String text, List<ContentBlock.Image> images) { return prompt(List.of(UserMessage.of(text, images))); }

    /// Another turn from the current transcript; steering messages, if any, are used.
    public AgentRun resume() { return start(List.of(), false); }

    /// Resume a checkpointed run (`AgentRun.state()`, persisted through [#codec]) with this agent's
    /// collaborators. Refused, naming the reason, if the run is finished or the tool set changed.
    public AgentRun resume(RunState persisted) {
        Objects.requireNonNull(persisted, "persisted");
        if (persisted.finished()) throw new IllegalStateException("run " + persisted.runId() + " is already finished");
        if (persisted.toolSetHash() != null && !persisted.toolSetHash().equals(cfg.tools().hash())) {
            throw new IllegalStateException("tool set changed since run " + persisted.runId() + " was checkpointed: "
                    + persisted.toolSetHash() + " vs " + cfg.tools().hash());
        }
        return launch(persisted);
    }

    /// The codec for persisting [AgentRun#state], aware of every registered custom message kind.
    public RunStateCodec codec() { return new RunStateCodec(cfg.codecs()); }

    private AgentRun start(List<AgentMessage> prompts, boolean skipInitialSteeringPoll) {
        synchronized (lock) {
            String runId = UUID.randomUUID().toString();
            return launch(RunEngine.start(prompts, transcript,
                    new RunOptions(cfg.limits(), skipInitialSteeringPoll, cfg.tools().hash()), runId, cfg.clock().instant()));
        }
    }

    private AgentRun launch(RunState initial) {
        synchronized (lock) {
            if (active != null) throw new IllegalStateException("a run is already active: " + active.runId());
            String runId = initial.runId();
            var cancel = Cancellation.create();
            var queue = new QueueSink();
            EventSink sink = EventSink.tee(List.of(fanout, queue, agentLevelQueues()));
            RunDeps deps = new RunDeps(cfg.provider(), cfg.tools(), cfg.hooks(), cfg.converter(), sink, cancel,
                    steering::drain, followUps::drain, cfg.requestTemplate().withSystemPrompt(systemPrompt()), cfg.clock());
            var run = new AgentRun(runId, cancel, queue, initial);
            active = run;
            last = run;
            idle = new CompletableFuture<>();
            runner.submit(() -> drive(run, initial, deps, sink));
            return run;
        }
    }

    private void drive(AgentRun run, RunState initial, RunDeps deps, EventSink sink) {
        var engine = new RunEngine(new TurnMachine(cfg.toolIdleTimeout()));
        RunState state = initial;
        RunResult result;
        try {
            Step step = engine.advance(state, deps);
            while (true) {
                state = step.state();
                run.checkpoint(state);
                if (step instanceof Step.Done done) { result = new RunResult(done.state(), done.outcome()); break; }
                step = engine.advance(state, deps);
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "run " + run.runId() + " failed", t);
            sink.fail(t);
            result = new RunResult(state, new RunOutcome.Failed(StopReason.ERROR, ToolFunnel.describe(t), t));
        } finally {
            sink.close();                                                   // non-negotiable
        }
        CompletableFuture<Void> wasIdle;
        synchronized (lock) {
            transcript = result.state().transcript();
            active = null;
            wasIdle = idle;
        }
        run.complete(result);
        wasIdle.complete(null);
    }

    /// The agent-level pull streams outlive runs: a run's `close()` must not end them.
    private EventSink agentLevelQueues() {
        return new EventSink() {
            @Override public void emit(AgentEvent event) { snapshot().forEach(q -> q.emit(event)); }
            @Override public void fail(Throwable cause)  { snapshot().forEach(q -> q.fail(cause)); }
            @Override public void close()                { }
            private List<QueueSink> snapshot() { synchronized (lock) { return List.copyOf(agentQueues); } }
        };
    }

    // ---- observation ---------------------------------------------------------------------------

    /// Ordered, sequential, isolated fan-out; the subscription outlives runs.
    public Subscription subscribe(AgentListener listener) { return fanout.subscribe(listener); }

    /// A one-shot, single-consumer, blocking stream over every event of every future run, until
    /// the stream is closed. Prefer [AgentRun#events] for one run.
    public Stream<AgentEvent> events() {
        var queue = new QueueSink();
        synchronized (lock) { agentQueues.add(queue); }
        return queue.stream().onClose(() -> { synchronized (lock) { agentQueues.remove(queue); } });
    }

    public AgentSnapshot state() {
        synchronized (lock) {
            AgentRun run = active != null ? active : last;
            RunState s = run == null ? null : run.state();
            return new AgentSnapshot(active != null, run == null ? null : run.runId(),
                    s == null ? -1 : s.turnIndex(), s == null ? 0 : s.turnsUsed(), s == null ? 0 : s.toolCallsUsed(),
                    s == null ? transcript.size() : s.transcript().size(), steering.size(), followUps.size());
        }
    }

    public List<AgentMessage> transcript() {
        synchronized (lock) { return active != null ? active.state().transcript() : transcript; }
    }

    public ToolRegistry tools() { return cfg.tools(); }

    /// The system prompt as it would be sent now (volatile sections render at call time).
    public String systemPrompt() { return SystemPromptBuilder.build(cfg.promptContext(), cfg.sections(), cfg.promptOverride()); }

    // ---- control -------------------------------------------------------------------------------

    public void steer(AgentMessage message) { steering.push(message); }

    public void followUp(AgentMessage message) { followUps.push(message); }

    /// Returns immediately; [#waitForIdle] is how you learn it stopped. Also clears both queues.
    public void abort() {
        AgentRun run;
        synchronized (lock) { run = active; }
        steering.clear();
        followUps.clear();
        if (run != null) run.abort();
    }

    public CompletableFuture<Void> waitForIdle() {
        synchronized (lock) { return idle; }
    }

    /// Aborts, waits, then clears the transcript and both queues.
    public void reset() {
        abort();
        waitForIdle().join();
        synchronized (lock) { transcript = List.of(); last = null; }
    }

    /// Aborts any run, waits briefly, closes extensions in reverse order (a throw from one does not
    /// stop the others) and releases the run executor.
    @Override public void close() {
        abort();
        try {
            waitForIdle().get(5, TimeUnit.SECONDS);
        } catch (Exception _) {
            // an unresponsive run is abandoned; its RunEnd still fires when it notices the cancel
        }
        for (Extension e : cfg.extensions().reversed()) {
            try { e.close(); } catch (Exception ex) { LOG.log(System.Logger.Level.WARNING, "extension " + e.id() + " close threw", ex); }
        }
        List<QueueSink> queues;
        synchronized (lock) { queues = List.copyOf(agentQueues); agentQueues.clear(); }
        queues.forEach(QueueSink::close);
        runner.shutdownNow();
    }
}
