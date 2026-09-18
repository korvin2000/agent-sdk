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
import sdk.agent.concurrent.Cancellation;
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
import sdk.agent.tool.ToolProvider;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.turn.TurnMachine;

/// The facade. Owns at most one live run, the two message queues, the listener fan-out and the
/// transcript between runs. `abort()` clears both queues; `reset()` aborts and waits first;
/// listener throws are isolated; late events never throw.
public final class Agent implements AutoCloseable {

    /// Everything the builder decided, immutable.
    record Config(LlmProvider provider,
                  List<ToolProvider> toolProviders,
                  AgentHooks hooks,
                  MessageConverter converter,
                  Map<String, AgentMessageCodec> codecs,
                  List<SectionSpec> sections,
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
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
    private final Object lock = new Object();

    private List<AgentMessage> transcript = List.of();      // guarded by lock
    private AgentRun active;                                // guarded by lock
    private AgentRun last;                                  // guarded by lock
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);   // guarded by lock
    private boolean closed;                                 // guarded by lock
    private Thread driver;                                  // guarded by lock

    Agent(Config cfg, ListenerFanout fanout) {
        this.cfg = Objects.requireNonNull(cfg);
        this.fanout = Objects.requireNonNull(fanout);
    }

    public static AgentBuilder builder() { return AgentBuilder.create(); }

    // ---- running -------------------------------------------------------------------------------

    /// @throws IllegalStateException if a run is active
    public AgentRun prompt(List<AgentMessage> messages) { return start(List.copyOf(messages)); }

    public AgentRun prompt(String text) { return prompt(List.of(UserMessage.text(text, cfg.clock().instant()))); }

    public AgentRun prompt(String text, List<ContentBlock.Image> images) {
        return prompt(List.of(UserMessage.of(text, images, cfg.clock().instant())));
    }

    /// Another turn from the current transcript; steering messages, if any, are used.
    public AgentRun resume() { return start(List.of()); }
    public AgentRun resume(RunState persisted) {
        Objects.requireNonNull(persisted, "persisted");
        synchronized (lock) { ensureOpen(); }
        RunStateCodec.validateDurable(persisted);
        if (persisted.finished()) throw new IllegalStateException("run " + persisted.runId() + " is already finished");
        ToolRegistry tools = tools();
        if (persisted.toolSetHash() != null && !persisted.toolSetHash().equals(tools.hash())) {
            throw new IllegalStateException("tool set changed since run " + persisted.runId() + " was checkpointed: "
                    + persisted.toolSetHash() + " vs " + tools.hash());
        }
        return launch(persisted, tools);
    }

    /// The codec for persisting [AgentRun#state], aware of every registered custom message kind.
    public RunStateCodec codec() { return new RunStateCodec(cfg.codecs()); }

    private AgentRun start(List<AgentMessage> prompts) {
        synchronized (lock) {
            ensureOpen();
            ToolRegistry tools = tools();
            String runId = UUID.randomUUID().toString();
            return launch(RunEngine.start(prompts, transcript, cfg.limits(), tools.hash(), runId, cfg.clock().instant()), tools);
        }
    }

    private AgentRun launch(RunState initial, ToolRegistry tools) {
        synchronized (lock) {
            ensureOpen();
            if (active != null) throw new IllegalStateException("a run is already active: " + active.runId());
            var cancel = Cancellation.create();
            var queue = new QueueSink();
            EventSink sink = EventSink.tee(List.of(fanout, queue));
            RunDeps deps = new RunDeps(cfg.provider(), tools, cfg.hooks(), cfg.converter(), sink, cancel,
                    steering::drain, followUps::drain, cfg.requestTemplate().withSystemPrompt(systemPrompt(tools)), cfg.clock());
            var run = new AgentRun(initial.runId(), cancel, queue, initial);
            var previousIdle = idle;
            active = run;
            last = run;
            idle = new CompletableFuture<>();
            try {
                runner.submit(() -> {
                    synchronized (lock) { driver = Thread.currentThread(); }
                    try { drive(run, initial, deps, sink); }
                    finally {
                        synchronized (lock) {
                            if (driver == Thread.currentThread()) driver = null;
                        }
                    }
                });
                return run;
            } catch (RuntimeException failure) {
                active = null;
                idle = previousIdle;
                var outcome = new RunOutcome.Failed(StopReason.ERROR, "run could not be submitted", failure);
                var result = new RunResult(initial, outcome);
                run.complete(result);
                previousIdle.complete(null);
                try {
                    sink.emit(new sdk.agent.event.AgentEvent.RunEnd(initial.runId(), sdk.agent.event.AgentEvent.RUN_SCOPED,
                            cfg.clock().instant(), initial.produced(), outcome));
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.WARNING, "failed to emit submission failure", t);
                } finally {
                    try { sink.close(); } catch (Throwable t) { LOG.log(System.Logger.Level.WARNING, "failed to close run events", t); }
                }
                return run;
            }
        }
    }
    private void drive(AgentRun run, RunState initial, RunDeps deps, EventSink sink) {
        RunState state = initial;
        RunResult result;
        try {
            var engine = new RunEngine(new TurnMachine(cfg.toolIdleTimeout()));
            Step step = engine.advance(state, deps);
            while (true) {
                state = step.state();
                run.checkpoint(state);
                if (step instanceof Step.Done done) { result = new RunResult(done.state(), done.outcome()); break; }
                step = engine.advance(state, deps);
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "run " + run.runId() + " failed", t);
            result = new RunResult(state, new RunOutcome.Failed(StopReason.ERROR, ToolFunnel.describe(t), t));
        } finally {
            try { sink.close(); } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "failed to close run events", t);
            }
        }
        finish(run, result);
    }

    private void finish(AgentRun run, RunResult result) {
        CompletableFuture<Void> wasIdle;
        synchronized (lock) {
            transcript = result.state().transcript();
            if (active == run) active = null;
            wasIdle = idle;
        }
        run.complete(result);
        wasIdle.complete(null);
    }

    // ---- observation ---------------------------------------------------------------------------

    /// Ordered, sequential, isolated fan-out; the subscription outlives runs. For a pull stream of
    /// one run use [AgentRun#events].
    public Subscription subscribe(AgentListener listener) { return fanout.subscribe(listener); }

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

    /// The tools as they would be registered for a run started now (a dynamic provider may have changed).
    public ToolRegistry tools() { return ToolRegistry.of(cfg.toolProviders()); }

    /// The system prompt as it would be sent now (volatile sections render at call time).
    public String systemPrompt() { return systemPrompt(tools()); }

    private String systemPrompt(ToolRegistry tools) {
        return SystemPromptBuilder.build(new PromptContext(tools.tools()), cfg.sections(), cfg.promptOverride());
    }

    // ---- control -------------------------------------------------------------------------------

    public void steer(AgentMessage message) {
        synchronized (lock) {
            ensureOpen();
            steering.push(message);
        }
    }

    public void followUp(AgentMessage message) {
        synchronized (lock) {
            ensureOpen();
            followUps.push(message);
        }
    }

    /// Returns without waiting; [#waitForIdle] is how you learn it stopped. Also clears both queues.
    public void abort() {
        AgentRun run;
        synchronized (lock) {
            ensureOpen();
            run = active;
            steering.clear();
            followUps.clear();
        }
        if (run != null) run.abort();
    }

    public CompletableFuture<Void> waitForIdle() {
        synchronized (lock) { return idle; }
    }

    /// Aborts, waits, then clears the transcript and both queues.
    public void reset() {
        synchronized (lock) { ensureOpen(); }
        abort();
        try {
            waitForIdle().join();
        } finally {
            synchronized (lock) {
                ensureOpen();
                transcript = List.of();
                last = null;
            }
        }
    }

    /// Aborts any run, waits briefly, closes extensions in reverse order (a throw from one does not
    /// stop the others) and releases the run executor.
    @Override public void close() {
        AgentRun run;
        boolean calledByDriver;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            run = active;
            calledByDriver = driver == Thread.currentThread();
            steering.clear();
            followUps.clear();
        }
        if (run != null) run.abort();
        try {
            if (!calledByDriver) waitForIdle().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception _) {
            // an unresponsive run is abandoned; its RunEnd still fires when it notices the cancel
        } finally {
            for (Extension e : cfg.extensions().reversed()) {
                try { e.close(); } catch (Exception ex) {
                    LOG.log(System.Logger.Level.WARNING, "extension " + e.id() + " close threw", ex);
                }
            }
            runner.shutdownNow();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("agent is closed");
    }
}
