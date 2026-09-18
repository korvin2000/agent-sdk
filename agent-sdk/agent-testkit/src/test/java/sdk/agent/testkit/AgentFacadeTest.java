package sdk.agent.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import sdk.agent.Agent;
import sdk.agent.AgentRun;
import sdk.agent.AgentSnapshot;
import sdk.agent.Phase;
import sdk.agent.RunLimits;
import sdk.agent.RunResult;
import sdk.agent.RunStateCodec;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.AgentHooks;
import sdk.agent.hook.TurnContext;
import sdk.agent.json.Json;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.spi.Contributions;
import sdk.agent.spi.Extension;
import sdk.agent.tool.Tool;

/// The facade: `abort()` clears the queues, `reset()` aborts and waits, `close()` is idempotent
/// and final, listener throws are isolated, late events never throw — plus the two completion
/// rules that keep the event side and the result side from starving each other.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Agent facade")
final class AgentFacadeTest {

    private final List<Agent> open = new CopyOnWriteArrayList<>();

    @AfterEach void closeAgents() {
        for (Agent a : open) {
            try { a.close(); } catch (RuntimeException _) { /* a stuck run is abandoned by close() */ }
        }
    }

    private Agent build(LlmProvider provider, Tool<?>... tools) {
        Agent agent = Agent.builder()
                .provider(provider)
                .turnGuard(null)                                   // loop policy is tested separately
                .limits(RunLimits.DEFAULTS.withMaxTurns(20))
                .toolIdleTimeout(Duration.ofMillis(200))
                .extension(new Extension() {
                    @Override public String id() { return "test"; }
                    @Override public Contributions contributions() { return Contributions.builder().tools(List.of(tools)).build(); }
                })
                .build();
        open.add(agent);
        return agent;
    }

    /// A provider whose stream never produces an event: the run parks until it is cancelled.
    private static ScriptedProvider hanging() {
        return ScriptedProvider.of(ScriptedProvider.emit(new LlmStreamEvent.Start()), ScriptedProvider.hang());
    }

    private static ScriptedProvider manyTextDeltas(int n) {
        var steps = new ArrayList<ScriptedProvider.Step>();
        steps.add(ScriptedProvider.emit(new LlmStreamEvent.Start()));
        steps.add(ScriptedProvider.emit(new LlmStreamEvent.TextStart(0)));
        for (int i = 0; i < n; i++) steps.add(ScriptedProvider.emit(new LlmStreamEvent.TextDelta(0, "x")));
        steps.add(ScriptedProvider.emit(new LlmStreamEvent.TextEnd(0, "x".repeat(n), null)));
        steps.add(ScriptedProvider.emit(new LlmStreamEvent.Done(StopReason.STOP, Usage.tokens(1, n), "resp-long")));
        return ScriptedProvider.of(steps);
    }

    private static CountDownLatch latchOn(Agent agent, Class<? extends AgentEvent> type) {
        var latch = new CountDownLatch(1);
        agent.subscribe(e -> { if (type.isInstance(e)) latch.countDown(); });
        return latch;
    }

    // ---- one live run --------------------------------------------------------------------------

    @Test
    @DisplayName("prompt() while a run is active throws, naming the run that holds the agent")
    void promptWhileActiveThrows() throws Exception {
        Agent agent = build(hanging());
        CountDownLatch started = latchOn(agent, AgentEvent.TurnStart.class);

        AgentRun run = agent.prompt("hi");
        assertTrue(started.await(10, TimeUnit.SECONDS));

        var thrown = assertThrows(IllegalStateException.class, () -> agent.prompt("again"));
        assertTrue(thrown.getMessage().contains(run.runId()));

        agent.abort();
        assertEquals(new RunOutcome.Aborted(), run.result().get(10, TimeUnit.SECONDS).outcome());
    }

    @Test
    @DisplayName("abort() clears BOTH queues and completes result() with Aborted, never exceptionally")
    void abortClearsQueuesAndCompletesWithAborted() throws Exception {
        Agent agent = build(hanging());
        CountDownLatch started = latchOn(agent, AgentEvent.TurnStart.class);
        AgentRun run = agent.prompt("hi");
        assertTrue(started.await(10, TimeUnit.SECONDS));

        agent.steer(UserMessage.text("change of plan"));
        agent.followUp(UserMessage.text("and then this"));
        assertEquals(1, agent.state().pendingSteering());
        assertEquals(1, agent.state().pendingFollowUps());

        agent.abort();

        RunResult result = run.result().get(10, TimeUnit.SECONDS);
        assertEquals(new RunOutcome.Aborted(), result.outcome());
        assertFalse(run.result().isCompletedExceptionally(), "a failure is a RunOutcome, not a thrown exception");
        assertEquals(0, agent.state().pendingSteering());
        assertEquals(0, agent.state().pendingFollowUps());
    }

    @Test
    @DisplayName("a provider that throws on open still completes the future with a Failed outcome")
    void aThrowingProviderIsDataNotAnException() throws Exception {
        Agent agent = build(ScriptedProvider.nonRetryable());
        RunResult result = agent.prompt("hi").result().get(10, TimeUnit.SECONDS);

        assertInstanceOf(RunOutcome.Failed.class, result.outcome());
        assertEquals(List.of("user", "assistant"), result.produced().stream().map(m -> m.kind()).toList());
    }

    @Test
    @DisplayName("waitForIdle() is how you learn a run stopped; it is already done when nothing runs")
    void waitForIdle() throws Exception {
        Agent agent = build(ScriptedProvider.simpleText());
        assertTrue(agent.waitForIdle().isDone(), "an agent with no run is idle");

        AgentRun run = agent.prompt("hi");
        agent.waitForIdle().get(10, TimeUnit.SECONDS);

        assertTrue(run.isDone());
        assertFalse(agent.state().running());
    }

    @Test
    @DisplayName("reset() aborts, waits, then clears the transcript and both queues")
    void reset() throws Exception {
        Agent agent = build(ScriptedProvider.simpleText());
        agent.prompt("hi").result().get(10, TimeUnit.SECONDS);
        assertEquals(2, agent.transcript().size());

        agent.steer(UserMessage.text("queued"));
        agent.reset();

        assertTrue(agent.transcript().isEmpty());
        assertEquals(0, agent.state().pendingSteering());
        assertEquals(0, agent.state().transcriptSize());
        assertFalse(agent.state().running());
    }

    @Test
    @DisplayName("close() is idempotent, closes each extension once and refuses new runs and messages")
    void closeIsIdempotentAndFinal() throws Exception {
        var closed = new AtomicInteger();
        Agent agent = Agent.builder().provider(ScriptedProvider.simpleText()).turnGuard(null)
                .extension(new Extension() {
                    @Override public String id() { return "close-count"; }
                    @Override public Contributions contributions() { return Contributions.NONE; }
                    @Override public void close() { closed.incrementAndGet(); }
                })
                .build();
        open.add(agent);
        AgentRun run = agent.prompt("hi");
        run.result().get(10, TimeUnit.SECONDS);

        agent.close();
        agent.close();

        assertEquals(1, closed.get());
        assertFalse(agent.state().running());
        assertThrows(IllegalStateException.class, () -> agent.prompt("after close"));
        assertThrows(IllegalStateException.class, agent::resume);
        assertThrows(IllegalStateException.class, () -> agent.resume(run.state()));
        assertThrows(IllegalStateException.class, agent::reset);
        assertThrows(IllegalStateException.class, () -> agent.steer(UserMessage.text("late")));
        assertThrows(IllegalStateException.class, () -> agent.followUp(UserMessage.text("late")));
        agent.abort();                                                       // harmless on a closed agent
    }

    // ---- checkpoint and resume -----------------------------------------------------------------

    @Test
    @DisplayName("resume skips the settled slot and keeps the turn's originally advertised tools")
    void resumeSkipsSettledSlotsAndKeepsTheTurnsAuthority() throws Exception {
        var read = FakeTool.mutating("read", "read result");
        var bash = FakeTool.mutating("bash", "must not run");
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).tools(read, bash).sequential()
                .hooks(new AgentHooks() {
                    @Override public LlmRequest beforeRequest(LlmRequest request, TurnContext ctx) {
                        return request.withTools(request.tools().stream().filter(t -> t.name().equals("read")).toList());
                    }
                });
        var driver = rig.driver("checkpoint").until(Phase.TOOLS_RUNNING).one();       // read ran; bash is pending
        var codec = RunStateCodec.builtIn();
        var checkpoint = codec.decode(Json.parse(codec.encode(driver.state()).toText()));

        Agent changedRegistry = build((_, _) -> { throw new AssertionError("provider must not open"); });
        assertThrows(IllegalStateException.class, () -> changedRegistry.resume(checkpoint), "the tool set changed");

        Agent agent = build(ScriptedProvider.simpleText(), read, bash);
        RunResult result = agent.resume(checkpoint).result().get(10, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals(1, read.calls(), "the durably settled call is not replayed");
        assertEquals(0, bash.calls(), "the original request never offered bash");
        var results = result.state().transcript().stream().filter(ToolResultMessage.class::isInstance).map(ToolResultMessage.class::cast).toList();
        assertEquals(2, results.size());
        assertTrue(results.getLast().isError());
        assertEquals(checkpoint.turn().pendingCalls().getFirst().id(), results.getLast().toolCallId());
    }

    // ---- observation ---------------------------------------------------------------------------

    @Test
    @DisplayName("a throwing listener stops neither the next listener nor the run")
    void listenerThrowsAreIsolated() throws Exception {
        Agent agent = build(ScriptedProvider.simpleText());
        var seen = new CopyOnWriteArrayList<String>();
        var alsoSeen = new CopyOnWriteArrayList<String>();

        agent.subscribe(e -> seen.add(e.getClass().getSimpleName()));
        agent.subscribe(_ -> { throw new IllegalStateException("render failed"); });
        agent.subscribe(e -> alsoSeen.add(e.getClass().getSimpleName()));

        RunResult result = agent.prompt("hi").result().get(10, TimeUnit.SECONDS);

        assertEquals(new RunOutcome.Completed(StopReason.STOP), result.outcome());
        assertEquals(seen, alsoSeen, "the listener after the throwing one sees exactly the same stream");
        assertEquals("RunStart", seen.getFirst());
        assertEquals("RunEnd", seen.getLast());
    }

    @Test
    @DisplayName("run.events() is this run's slice and ends after RunEnd")
    void runEventsEndAfterRunEnd() throws Exception {
        Agent agent = build(ScriptedProvider.simpleText());
        AgentRun run = agent.prompt("hi");

        List<AgentEvent> events = run.events().toList();          // terminates: the sink is closed in a finally

        assertEquals("RunStart", events.getFirst().getClass().getSimpleName());
        assertEquals("RunEnd", events.getLast().getClass().getSimpleName());
        assertTrue(events.stream().allMatch(e -> e.runId().equals(run.runId())));

        var sink = new RecordingSink();
        events.forEach(sink::emit);
        sink.assertInvariants();
    }

    @Test
    @DisplayName("closing a partially consumed event stream abandons observation without stranding the run")
    void closingAPartialEventStreamDoesNotStrandTheRun() throws Exception {
        Agent agent = build(hanging());
        AgentRun run = agent.prompt("hi");
        try (var events = run.events()) {
            assertInstanceOf(AgentEvent.RunStart.class, events.iterator().next());
        }
        agent.abort();
        assertEquals(new RunOutcome.Aborted(), run.result().get(10, TimeUnit.SECONDS).outcome());
    }

    @Test
    @DisplayName("AgentSnapshot counts turns, tool calls and transcript entries")
    void snapshotCounts() throws Exception {
        Agent agent = build(ScriptedProvider.defaultScenario(),
                FakeTool.readOnly("read", "contents"), FakeTool.readOnly("bash", "total 0"));

        assertEquals(new AgentSnapshot(false, null, -1, 0, 0, 0, 0, 0), agent.state());

        RunResult result = agent.prompt("hi").result().get(10, TimeUnit.SECONDS);
        assertEquals(new RunOutcome.Completed(StopReason.STOP), result.outcome());

        AgentSnapshot snapshot = agent.state();
        assertFalse(snapshot.running());
        assertEquals(2, snapshot.turnsUsed(), "the tool turn and the closing text turn");
        assertEquals(2, snapshot.toolCallsUsed());
        assertEquals(5, snapshot.transcriptSize(), "prompt, assistant, two results, closing assistant");
        assertEquals(1, snapshot.turnIndex());
        assertEquals(0, snapshot.pendingSteering());
    }

    @Test
    @DisplayName("a run nobody pulls still finishes: the newest events are kept, the run is never backpressured")
    void unconsumedEventQueueDoesNotBlockTheRun() throws Exception {
        Agent agent = build(manyTextDeltas(600));                  // far past QueueSink's 256 capacity

        AgentRun run = agent.prompt("hi");
        RunResult result = run.result().get(15, TimeUnit.SECONDS);
        assertEquals(new RunOutcome.Completed(StopReason.STOP), result.outcome());

        List<AgentEvent> retained = run.events().toList();
        assertTrue(retained.size() <= 256, "the queue is bounded: " + retained.size());
        assertInstanceOf(AgentEvent.RunEnd.class, retained.getLast(),
                "the newest are kept, so the terminal event survives however far behind a late consumer is");
    }
}
