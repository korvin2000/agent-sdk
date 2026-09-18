package sdk.agent.testkit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import sdk.agent.Phase;
import sdk.agent.RunLimits;
import sdk.agent.RunResult;
import sdk.agent.RunState;
import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.AgentHooks;
import sdk.agent.hook.TurnContext;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ToolMessages;
import sdk.agent.tool.ToolResult;
import sdk.agent.turn.TurnMachine;

/// The engine phase table driven with a [ScriptedProvider] and [FakeTool]s, asserting
/// **pi's documented event trace with the two documented deviations** (prompt messages before the
/// first `TurnStart`; tool results in assistant source order) plus [RecordingSink#assertInvariants].
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("RunEngine — event traces and the run-level table")
final class RunEngineTraceTest {

    // ---- trace helper --------------------------------------------------------------------------

    private static final class T {
        private final List<String> out = new ArrayList<>();
        T e(String... names)  { out.addAll(List.of(names)); return this; }
        T updates(int n)      { for (int i = 0; i < n; i++) out.add("MessageUpdate"); return this; }
        /// The prompt's own message pair, emitted run-scoped before the first `TurnStart`.
        T prompt()            { return e("RunStart", "MessageStart", "MessageEnd"); }
        /// A whole assistant message: open, `n` updates, close.
        T assistant(int n)    { return e("MessageStart").updates(n).e("MessageEnd"); }
        /// One tool call announced, resolved and written into the transcript.
        T tool()              { return e("ToolEnd", "MessageStart", "MessageEnd"); }
        /// A slot that never ran: its padded result, as a message pair.
        T pad()               { return e("MessageStart", "MessageEnd"); }
        /// The closing text turn every script falls back to once its scripts run out.
        T fallbackTurn()      { return e("TurnStart").assistant(3).e("TurnEnd"); }
        List<String> list()   { return List.copyOf(out); }
    }

    private static T t() { return new T(); }

    // ---- the simple rows -----------------------------------------------------------------------

    @Test
    @DisplayName("a single text turn: prompt, turn, run end — and nothing else")
    void singleTextTurn() {
        var rig = new Rig().provider(ScriptedProvider.simpleText());
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt().e("TurnStart").assistant(3).e("TurnEnd", "RunEnd").list(), rig.trace());
        assertEquals(new RunOutcome.Completed(StopReason.STOP), result.outcome());
        assertEquals(List.of("user", "assistant"), kinds(result.produced()));
        assertEquals("Hello, world!", ((AssistantMessage) result.produced().get(1)).text());
    }

    @Test
    @DisplayName("two READ_ONLY calls run as one parallel batch, results in assistant source order (I11)")
    void twoReadOnlyToolsRunAsOneBatchInSourceOrder() {
        var secondFinished = new CountDownLatch(1);
        var read = FakeTool.named("read").kind(sdk.agent.tool.ToolKind.READ_ONLY)
                .blockingOn(secondFinished).answering("contents of file.txt");
        var bash = FakeTool.named("bash").kind(sdk.agent.tool.ToolKind.READ_ONLY)
                .doing(_ -> { secondFinished.countDown(); return ToolResult.text("total 0"); });

        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).tools(read, bash);
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt()
                        .e("TurnStart").assistant(10)
                        .e("ToolStart", "ToolStart").tool().tool().e("TurnEnd")
                        .fallbackTurn().e("RunEnd").list(),
                rig.trace(),
                "both ToolStarts are announced before either runs; the two results drain strictly by index");

        List<String> resultOrder = rig.sink.ofType(AgentEvent.ToolEnd.class).stream().map(AgentEvent.ToolEnd::toolCallId).toList();
        assertEquals(List.of("call-1", "call-2"), resultOrder,
                "call-2 finished first; the results must still be emitted in assistant source order");
        assertEquals("contents of file.txt", rig.sink.toolResult("call-1").orElseThrow().text());

        var turnEnd = rig.sink.ofType(AgentEvent.TurnEnd.class).getFirst();
        assertEquals(List.of("call-1", "call-2"), turnEnd.toolResults().stream().map(ToolResultMessage::toolCallId).toList());
    }

    @Test
    @DisplayName("a MUTATING call is a batch of one: batches partition in source order")
    void mutatingAndReadOnlyMixAreSeparateBatches() {
        var read = FakeTool.mutating("read", "contents");        // MUTATING first -> singleton batch
        var bash = FakeTool.readOnly("bash", "total 0");

        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).tools(read, bash);
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt()
                        .e("TurnStart").assistant(10)
                        .e("ToolStart").tool()
                        .e("ToolStart").tool()
                        .e("TurnEnd")
                        .fallbackTurn().e("RunEnd").list(),
                rig.trace(),
                "a mutating call is announced and resolved alone before the next batch is announced");
    }

    @Test
    @DisplayName("SEQUENTIAL pins every batch to one, with the same rule and the same trace shape")
    void sequentialModeRunsOneCallPerBatch() {
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).sequential()
                .tools(FakeTool.readOnly("read", "contents"), FakeTool.readOnly("bash", "total 0"));
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt()
                        .e("TurnStart").assistant(10)
                        .e("ToolStart").tool()
                        .e("ToolStart").tool()
                        .e("TurnEnd")
                        .fallbackTurn().e("RunEnd").list(),
                rig.trace());
    }

    // ---- steering and follow-ups ---------------------------------------------------------------

    @Test
    @DisplayName("I9: a steered message opens the NEXT turn, never splitting an assistant/result pair")
    void steeringIsDeferredToTheNextTurnOpening() {
        var rig = new Rig().provider(ScriptedProvider.thinkingTextTool());
        var read = FakeTool.named("read").kind(sdk.agent.tool.ToolKind.READ_ONLY).doing(_ -> {
            rig.steering.push(UserMessage.text("actually, stop there", rig.clock.instant()));
            return ToolResult.text("contents");
        });
        rig.tools(read);
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt()
                        .e("TurnStart").assistant(8)
                        .e("ToolStart").tool()
                        .e("TurnEnd")
                        .e("MessageStart", "MessageEnd")            // the steered message, between turns
                        .fallbackTurn().e("RunEnd").list(),
                rig.trace(),
                "steering is drained at TURN_CLOSED and emitted at the next TURN_OPENING");

        assertEquals(List.of("user", "assistant", "toolResult", "user", "assistant"), kinds(result.produced()));
        assertEquals("actually, stop there", ((UserMessage) result.produced().get(3)).text());
    }

    @Test
    @DisplayName("a follow-up drains at FOLLOW_UP — after the turn closed with no tool calls")
    void followUpOpensAnotherTurnAfterTheTurnCloses() {
        var rig = new Rig().provider(ScriptedProvider.simpleText());
        rig.followUps.push(UserMessage.text("and then?", rig.clock.instant()));
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt()
                        .e("TurnStart").assistant(3).e("TurnEnd")
                        .e("MessageStart", "MessageEnd")
                        .fallbackTurn().e("RunEnd").list(),
                rig.trace());
        assertEquals(List.of("user", "assistant", "user", "assistant"), kinds(result.produced()));
        assertEquals(2, rig.sink.ofType(AgentEvent.TurnStart.class).size());
    }

    // ---- failure path --------------------------------------------------------------------------

    @Test
    @DisplayName("a mid-stream Failed event: RunOutcome.Failed and a COMPLETE RunEnd.produced")
    void failurePathCarriesTheWholeProducedList() {
        var rig = new Rig().provider(ScriptedProvider.streamError());
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt().e("TurnStart").assistant(3).e("TurnEnd", "RunEnd").list(), rig.trace());

        var failed = assertInstanceOf(RunOutcome.Failed.class, result.outcome());
        assertEquals("upstream closed the connection", failed.message());

        var runEnd = rig.sink.ofType(AgentEvent.RunEnd.class).getFirst();
        assertSame(result.outcome(), runEnd.outcome(), "the future and the event can never disagree");
        assertEquals(List.of("user", "assistant"), kinds(runEnd.produced()),
                "pi drops agent_end.messages on the failure path; ours must carry the prompt AND the failed turn");
        var assistant = (AssistantMessage) runEnd.produced().get(1);
        assertAll(
                () -> assertEquals(StopReason.ERROR, assistant.stopReason()),
                () -> assertEquals("Before error", assistant.text(), "partial text is finalised, once"),
                () -> assertEquals("upstream closed the connection", assistant.errorMessage()));
    }

    @Test
    @DisplayName("provider-side retry is invisible to the core; exhaustion is an ERROR turn, not a throw")
    void retryIsProviderSideAndExhaustionIsData() {
        var retrying = ScriptedProvider.retries();
        var rig = new Rig().provider(retrying);
        RunResult ok = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.Completed(StopReason.STOP), ok.outcome());
        assertEquals(List.of(2), retrying.attemptsPerOpen(), "two retryable opens were burned inside the provider");
        assertEquals(1, rig.sink.ofType(AgentEvent.TurnStart.class).size(), "a retried open is still one turn");

        var exhausted = new Rig().provider(ScriptedProvider.retryExhausted());
        var failed = assertInstanceOf(RunOutcome.Failed.class, exhausted.outcome("hi"));
        assertEquals("IllegalStateException: upstream unavailable", failed.message());
        exhausted.sink.assertInvariants();

        var immediate = ScriptedProvider.nonRetryable();
        var nonRetryable = new Rig().provider(immediate);
        assertInstanceOf(RunOutcome.Failed.class, nonRetryable.outcome("hi"));
        assertEquals(List.of(1), immediate.attemptsPerOpen(), "a non-retryable failure burns exactly one attempt");
    }

    @Test
    @DisplayName("the attempt counter resets per stream() call (kon K-10)")
    void attemptStateResetsPerStreamCall() {
        List<ScriptedProvider.Step> retrying = ScriptedProvider.retries().script();
        var provider = ScriptedProvider.sequence(List.of(retrying, retrying));

        new Rig().provider(provider).run("first");
        new Rig().provider(provider).run("second");

        assertEquals(List.of(2, 2), provider.attemptsPerOpen(),
                "reusing one provider must not make the second run succeed on attempt 1");
    }

    @Test
    @DisplayName("a provider whose next() throws still finalises the turn on every path")
    void providerNextThrowingKeepsTheInvariants() {
        LlmProvider exploding = (request, cancel) -> new sdk.agent.provider.LlmStream() {
            private int emitted;
            @Override public sdk.agent.provider.LlmStreamEvent next() {
                if (emitted++ == 0) return new sdk.agent.provider.LlmStreamEvent.TextStart(0);
                throw new IllegalStateException("next() blew up");
            }
            @Override public void close() { }
        };
        var rig = new Rig().provider(exploding);
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertInstanceOf(RunOutcome.Failed.class, result.outcome());
        assertEquals(List.of("user", "assistant"), kinds(result.produced()));
    }

    // ---- the abort checkpoints -----------------------------------------------------------------

    @Test
    @DisplayName("ck1: cancelled before the first turn — no TurnStart is ever emitted")
    void abortAtCheckpointOneBeforeTheFirstTurn() {
        var rig = new Rig().provider(ScriptedProvider.simpleText());
        var driver = rig.driver("hi").until(Phase.TURN_OPENING);
        rig.cancel.cancel();
        RunResult result = driver.finish();

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.Aborted(), result.outcome());
        assertEquals(t().prompt().e("RunEnd").list(), rig.trace());
    }

    @Test
    @DisplayName("ck2: cancelled by a beforeTurn hook — after injection, before the request is built")
    void abortAtCheckpointTwoInsideTurnOpening() {
        var rig = new Rig().provider(ScriptedProvider.simpleText());
        rig.hooks(new AgentHooks() {
            @Override public List<AgentMessage> beforeTurn(TurnContext ctx) {
                rig.cancel.cancel();
                return List.of(UserMessage.text("injected reminder", ctx.clock().instant()));
            }
        });
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.Aborted(), result.outcome());
        assertEquals(t().prompt().e("MessageStart", "MessageEnd", "RunEnd").list(), rig.trace(),
                "the injected message is durable and still emitted; the stream never opens");
    }

    @Test
    @DisplayName("ck3: cancelled between batches — unfilled slots are padded and one TurnEnd is emitted")
    void abortAtCheckpointThreeBetweenToolBatches() {
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).sequential()
                .tools(FakeTool.mutating("read", "contents"), FakeTool.mutating("bash", "total 0"));
        var driver = rig.driver("hi").until(Phase.TOOLS_RUNNING).one();       // first batch runs
        rig.cancel.cancel();
        RunResult result = driver.finish();

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.Aborted(), result.outcome());
        assertEquals(t().prompt()
                        .e("TurnStart").assistant(10)
                        .e("ToolStart").tool()
                        .pad()
                        .e("TurnEnd", "RunEnd").list(),
                rig.trace());

        var turnEnd = rig.sink.ofType(AgentEvent.TurnEnd.class).getFirst();
        assertEquals(2, turnEnd.toolResults().size(), "I10 holds on the abort path too");
        assertEquals(ToolMessages.NOT_EXECUTED, turnEnd.toolResults().get(1).text());
        assertTrue(turnEnd.toolResults().get(1).isError());
    }

    @Test
    @DisplayName("ck4: cancelled once the turn is closed — no second TurnEnd")
    void abortAtCheckpointFourAfterTheTurnCloses() {
        var rig = new Rig().provider(ScriptedProvider.simpleText());
        var driver = rig.driver("hi").until(Phase.TURN_CLOSED);
        rig.cancel.cancel();
        RunResult result = driver.finish();

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.Aborted(), result.outcome());
        assertEquals(1, rig.sink.ofType(AgentEvent.TurnEnd.class).size());
        assertEquals("RunEnd", rig.trace().getLast());
    }

    @Test
    @DisplayName("ck5: cancelled at the follow-up poll — the queued follow-up never opens a turn")
    void abortAtCheckpointFiveAtTheFollowUpPoll() {
        var rig = new Rig().provider(ScriptedProvider.simpleText());
        rig.followUps.push(UserMessage.text("and then?", rig.clock.instant()));
        var driver = rig.driver("hi").until(Phase.FOLLOW_UP);
        rig.cancel.cancel();
        RunResult result = driver.finish();

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.Aborted(), result.outcome());
        assertEquals(1, rig.sink.ofType(AgentEvent.TurnStart.class).size());
    }

    @Test
    @DisplayName("a tool in flight is interrupted and completed with the deliberate cancelled string")
    void abortDuringToolExecutionCompletesTheOpenCall() throws Exception {
        var gate = new CountDownLatch(1);
        var read = FakeTool.named("read").kind(sdk.agent.tool.ToolKind.READ_ONLY).blockingOn(gate).answering("never");
        var bash = FakeTool.named("bash").kind(sdk.agent.tool.ToolKind.READ_ONLY).blockingOn(gate).answering("never");
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).tools(read, bash);

        CompletableFuture<RunResult> run = CompletableFuture.supplyAsync(() -> rig.run("hi"));
        try {
            assertTrue(read.started().await(5, TimeUnit.SECONDS), "the first call must reach its body");
            assertTrue(bash.started().await(5, TimeUnit.SECONDS), "a parallel batch launches both");
            rig.cancel.cancel();
            RunResult result = run.get(10, TimeUnit.SECONDS);

            rig.sink.assertInvariants();
            assertEquals(new RunOutcome.Aborted(), result.outcome());
            assertAll(
                    () -> assertEquals(ToolMessages.CANCELLED, rig.sink.toolResult("call-1").orElseThrow().text()),
                    () -> assertEquals(ToolMessages.CANCELLED, rig.sink.toolResult("call-2").orElseThrow().text()),
                    () -> assertTrue(rig.sink.toolResult("call-1").orElseThrow().isError()),
                    () -> assertEquals("RunEnd", rig.trace().getLast()));
        } finally {
            gate.countDown();
        }
    }

    // ---- limits --------------------------------------------------------------------------------

    @Test
    @DisplayName("the wall-clock limit takes the abort path and reports LimitExceeded(WALL_CLOCK)")
    void wallClockLimitEndsTheRun() {
        var rig = new Rig()
                .provider(ScriptedProvider.simpleText())
                .limits(RunLimits.DEFAULTS.withMaxTurns(20).withWallClock(Duration.ofSeconds(30)));
        var driver = rig.driver("hi").until(Phase.TURN_CLOSED);
        rig.clock.advance(Duration.ofSeconds(31));
        RunResult result = driver.finish();

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.LimitExceeded(RunOutcome.Limit.WALL_CLOCK, "wall clock exceeded: PT30S"), result.outcome());
        assertEquals("RunEnd", rig.trace().getLast());
    }

    @Test
    @DisplayName("the wall clock is checked before a turn opens, too (ck1)")
    void wallClockLimitIsCheckedBeforeATurnOpens() {
        var rig = new Rig()
                .provider(ScriptedProvider.simpleText())
                .limits(RunLimits.DEFAULTS.withMaxTurns(20).withWallClock(Duration.ofSeconds(5)));
        var driver = rig.driver("hi").until(Phase.TURN_OPENING);
        rig.clock.advance(Duration.ofSeconds(6));
        RunResult result = driver.finish();

        rig.sink.assertInvariants();
        assertInstanceOf(RunOutcome.LimitExceeded.class, result.outcome());
        assertEquals(t().prompt().e("RunEnd").list(), rig.trace(), "no turn is opened past the deadline");
    }

    @Test
    @DisplayName("maxToolCalls stops the run before a batch launches, padding the slots it refused")
    void maxToolCallsCapPadsTheRemainingSlots() {
        var read = FakeTool.readOnly("read", "contents");
        var bash = FakeTool.readOnly("bash", "total 0");
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).sequential()
                .limits(RunLimits.DEFAULTS.withMaxTurns(20).withMaxToolCalls(1)
                        .withToolExecution(sdk.agent.ToolExecutionMode.SEQUENTIAL))
                .tools(read, bash);
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TOOL_CALLS, "tool call cap reached: 1"), result.outcome());
        assertEquals(t().prompt()
                        .e("TurnStart").assistant(10)
                        .e("ToolStart").tool()
                        .pad()
                        .e("TurnEnd", "RunEnd").list(),
                rig.trace());
        assertEquals(1, read.calls(), "the cap is checked before a batch launches, never after");
        assertEquals(0, bash.calls());

        var turnEnd = rig.sink.ofType(AgentEvent.TurnEnd.class).getFirst();
        assertEquals(ToolMessages.NOT_EXECUTED, turnEnd.toolResults().get(1).text());
    }

    @Test
    @DisplayName("the tool-call cap comparison cannot overflow near Integer.MAX_VALUE")
    void toolCallBudgetComparisonCannotOverflow() {
        var read = FakeTool.readOnly("read", "contents");
        var bash = FakeTool.readOnly("bash", "output");
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).tools(read, bash);
        RunState s = rig.driver("hi").until(Phase.TOOLS_RUNNING).state();
        var nearCap = new RunState(s.runId(), s.phase(), s.turnIndex(), s.transcript(), s.seedSize(), s.pendingInjection(),
                s.turn(), s.limits(), s.turnsUsed(), Integer.MAX_VALUE - 1, s.usage(), s.startedAt(), s.outcome(), s.toolSetHash());

        RunResult result = rig.engine().run(nearCap, rig.deps());

        rig.sink.assertInvariants();
        assertEquals(RunOutcome.Limit.MAX_TOOL_CALLS, assertInstanceOf(RunOutcome.LimitExceeded.class, result.outcome()).limit());
        assertEquals(0, read.calls() + bash.calls());
    }

    @Test
    @DisplayName("the turn budget is the engine's: a host Retry loop stops at maxTurns without any TurnGuard")
    void retryBudgetIsEnforcedWithoutTurnGuard() {
        var provider = ScriptedProvider.simpleText();
        var rig = new Rig().provider(provider).limits(RunLimits.DEFAULTS.withMaxTurns(2)).hooks(new AgentHooks() {
            @Override public TurnVerdict afterAssistant(AssistantMessage message, TurnContext ctx) {
                return new TurnVerdict.Retry(UserMessage.text("Again", ctx.clock().instant()), "host retry");
            }
        });

        var outcome = assertInstanceOf(RunOutcome.LimitExceeded.class, rig.run("hi").outcome());
        rig.sink.assertInvariants();
        assertEquals(RunOutcome.Limit.MAX_TURNS, outcome.limit());
        assertEquals(2, provider.opens());
    }

    @Test
    @DisplayName("a final answer on the last allowed turn completes; a follow-up cannot open one more")
    void finalResponseAtTurnLimitCompletesButFollowUpCannotExceedIt() {
        var provider = ScriptedProvider.simpleText();
        assertInstanceOf(RunOutcome.Completed.class,
                new Rig().provider(provider).limits(RunLimits.DEFAULTS.withMaxTurns(1)).run("hi").outcome());

        var follow = new Rig().provider(provider).limits(RunLimits.DEFAULTS.withMaxTurns(1));
        follow.followUps.push(UserMessage.text("Again", follow.clock.instant()));
        assertEquals(RunOutcome.Limit.MAX_TURNS,
                assertInstanceOf(RunOutcome.LimitExceeded.class, follow.run("hi").outcome()).limit());
        follow.sink.assertInvariants();
        assertEquals(2, provider.opens());
    }

    // ---- verdicts and padding --------------------------------------------------------------------

    @Test
    @DisplayName("a Stop verdict pads every call before its own message: the transcript stays provider-valid")
    void stopPadsBothCallsBeforeItsMessage() {
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).hooks(new AgentHooks() {
            @Override public TurnVerdict afterAssistant(AssistantMessage message, TurnContext ctx) {
                return new TurnVerdict.Stop(new RunOutcome.Completed(StopReason.STOP), UserMessage.text("Stopped by host", ctx.clock().instant()));
            }
        });
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(t().prompt().e("TurnStart").assistant(10).pad().pad().e("TurnEnd", "MessageStart", "MessageEnd", "RunEnd").list(),
                rig.trace(), "a veto has no side effect to undo, but every call still gets its result");
        assertEquals(List.of("user", "assistant", "toolResult", "toolResult", "user"), kinds(result.produced()));
        assertTrue(rig.sink.ofType(AgentEvent.ToolStart.class).isEmpty());
        assertEquals(List.of("call-1", "call-2"), rig.sink.ofType(AgentEvent.TurnEnd.class).getFirst()
                .toolResults().stream().map(ToolResultMessage::toolCallId).toList());
    }

    @Test
    @DisplayName("a Retry verdict pads every call, then reprompts at the next opening")
    void retryPadsBothCallsBeforeTheReprompt() {
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).hooks(new AgentHooks() {
            @Override public TurnVerdict afterAssistant(AssistantMessage message, TurnContext ctx) {
                return message.toolCalls().isEmpty() ? TurnVerdict.PROCEED
                        : new TurnVerdict.Retry(UserMessage.text("Try again", ctx.clock().instant()), "host policy");
            }
        });
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(List.of("user", "assistant", "toolResult", "toolResult", "user", "assistant"), kinds(result.produced()));
        assertTrue(rig.sink.ofType(AgentEvent.ToolStart.class).isEmpty());
        assertTrue(rig.sink.ofType(AgentEvent.TurnEnd.class).getFirst().toolResults().stream().allMatch(ToolResultMessage::isError));
    }

    @Test
    @DisplayName("ERROR, ABORTED and LENGTH turns never execute the calls they collected, with or without a guard")
    void unsuccessfulProviderTurnsNeverExecuteCollectedCalls() {
        for (StopReason reason : List.of(StopReason.ERROR, StopReason.ABORTED, StopReason.LENGTH)) {
            var script = new ArrayList<>(ScriptedProvider.toolCall(0, "call-1", "write", "{\"path\":\"secret.txt\"}"));
            script.addFirst(ScriptedProvider.emit(new LlmStreamEvent.Start()));
            script.add(ScriptedProvider.emit(new LlmStreamEvent.Done(reason, Usage.EMPTY, "response")));
            var write = FakeTool.mutating("write", "written");
            var rig = new Rig().provider(ScriptedProvider.of(script)).tools(write);
            rig.run("hi");

            rig.sink.assertInvariants();
            assertEquals(0, write.calls(), reason.name());
            assertEquals(ToolMessages.NOT_EXECUTED, rig.sink.toolResult("call-1").orElseThrow().text(), reason.name());
            assertTrue(rig.sink.ofType(AgentEvent.ToolStart.class).isEmpty(), reason.name());
        }
    }

    @Test
    @DisplayName("a beforeRequest hook that strips tools also strips the authority to run them")
    void requestToolFilteringRemovesExecutionAuthority() {
        var read = FakeTool.readOnly("read", "secret");
        var rig = new Rig().provider(ScriptedProvider.thinkingTextTool()).tools(read).hooks(new AgentHooks() {
            @Override public LlmRequest beforeRequest(LlmRequest request, TurnContext ctx) { return request.withTools(List.of()); }
        });
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(0, read.calls());
        assertEquals(ToolMessages.NOT_ADVERTISED.formatted("read"), rig.sink.toolResult("call-1").orElseThrow().text());
    }

    // ---- hooks fail closed -----------------------------------------------------------------------

    @Test
    @DisplayName("a failing transformContext ends the run before the provider is called, keeping what was injected")
    void contextPolicyFailureRetainsInjectedMessagesWithoutOpeningTheProvider() {
        var provider = ScriptedProvider.simpleText();
        var rig = new Rig().provider(provider).hooks(new AgentHooks() {
            @Override public List<AgentMessage> beforeTurn(TurnContext ctx) {
                return List.of(UserMessage.text("Durable injection", ctx.clock().instant()));
            }
            @Override public List<AgentMessage> transformContext(List<AgentMessage> messages, Cancellation cancel) {
                throw new IllegalStateException("context policy unavailable");
            }
        });
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertInstanceOf(RunOutcome.Failed.class, result.outcome());
        assertEquals(0, provider.opens());
        assertEquals(List.of("user", "user"), kinds(result.produced()));
        assertEquals(result.produced(), rig.sink.first(AgentEvent.RunEnd.class).produced());
    }

    @Test
    @DisplayName("a failing beforeRequest or converter never sends fallback data")
    void requestPolicyAndConversionFailuresNeverSendFallbackData() {
        var provider = ScriptedProvider.simpleText();
        var requestRig = new Rig().provider(provider).hooks(new AgentHooks() {
            @Override public LlmRequest beforeRequest(LlmRequest request, TurnContext ctx) {
                throw new IllegalStateException("request policy unavailable");
            }
        });
        assertInstanceOf(RunOutcome.Failed.class, requestRig.run("hi").outcome());
        requestRig.sink.assertInvariants();

        var converterRig = new Rig().provider(provider);
        converterRig.converter = _ -> { throw new IllegalStateException("conversion unavailable"); };
        assertInstanceOf(RunOutcome.Failed.class, converterRig.run("hi").outcome());
        converterRig.sink.assertInvariants();
        assertEquals(0, provider.opens());
    }

    @Test
    @DisplayName("a failing afterAssistant pads the turn's calls and ends the run with one consistent RunEnd")
    void assistantPolicyFailurePadsCurrentCallsAndProducesOneConsistentEnd() {
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).hooks(new AgentHooks() {
            @Override public TurnVerdict afterAssistant(AssistantMessage message, TurnContext ctx) {
                throw new IllegalStateException("assistant policy unavailable");
            }
        });
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertInstanceOf(RunOutcome.Failed.class, result.outcome());
        assertEquals(List.of("user", "assistant", "toolResult", "toolResult"), kinds(result.produced()));
        assertEquals(result.produced(), rig.sink.first(AgentEvent.RunEnd.class).produced());
        assertEquals(1, rig.sink.ofType(AgentEvent.RunEnd.class).size());
    }

    // ---- observation -----------------------------------------------------------------------------

    @Test
    @DisplayName("beforeToolCall runs sequentially in source order even inside a parallel batch")
    void beforeToolCallIsSequentialInsideAParallelBatch() {
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var gate = new CountDownLatch(1);
        var read = FakeTool.named("read").kind(sdk.agent.tool.ToolKind.READ_ONLY).blockingOn(gate).answering("a");
        var bash = FakeTool.named("bash").kind(sdk.agent.tool.ToolKind.READ_ONLY)
                .doing(_ -> { gate.countDown(); return ToolResult.text("b"); });

        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).tools(read, bash)
                .hooks(new AgentHooks() {
                    @Override public sdk.agent.hook.ToolDecision beforeToolCall(
                            sdk.agent.hook.BeforeToolCall call, Cancellation cancel) {
                        order.add(call.toolCall().id());
                        return sdk.agent.hook.ToolDecision.ALLOW;
                    }
                });
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(List.of("call-1", "call-2"), order, "a permission prompt must not race");
    }

    @Test
    @DisplayName("ToolUpdate is emitted between a call's ToolStart and its ToolEnd")
    void toolProgressIsBracketedByTheCall() {
        var read = FakeTool.named("read").kind(sdk.agent.tool.ToolKind.READ_ONLY).doing(call -> {
            call.progress().update(ToolResult.text("read 50%"));
            return ToolResult.text("done");
        });
        var rig = new Rig().provider(ScriptedProvider.thinkingTextTool()).tools(read);
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(1, rig.sink.ofType(AgentEvent.ToolUpdate.class).size());
        assertEquals("read 50%", rig.sink.ofType(AgentEvent.ToolUpdate.class).getFirst().partial().text());
    }

    // ---- the stall path ------------------------------------------------------------------------

    @Test
    @DisplayName("10: a stall is a failed turn — the complete call is kept for diagnosis but never executed")
    void stalledTurnIsAFailedTurn() {
        var read = FakeTool.readOnly("read", "contents of file.txt");
        var rig = new Rig().provider(ScriptedProvider.toolHang()).idleTimeout(Duration.ofMillis(120)).tools(read);
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(TurnMachine.STALLED, assertInstanceOf(RunOutcome.Failed.class, result.outcome()).message());
        assertEquals(0, read.calls());
        assertTrue(rig.sink.ofType(AgentEvent.ToolStart.class).isEmpty());
        assertEquals(ToolMessages.NOT_EXECUTED, rig.sink.toolResult("call-1").orElseThrow().text());
    }

    @Test
    @DisplayName("12: the stale snapshot never reaches the tool — the write to /tmp/stale.txt does not happen")
    void staleSnapshotIsNeverExecuted() {
        var write = FakeTool.mutating("write", "written");
        var rig = new Rig().provider(ScriptedProvider.toolHangWithInitialArgs())
                .idleTimeout(Duration.ofMillis(120)).tools(write);
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(0, write.calls(), "falling back to initialArguments after a stall would write the stale path");
        assertTrue(rig.sink.toolResult("call-1").orElseThrow().isError());
    }

    @Test
    @DisplayName("every stream the provider hands out is closed by the pump, on the stall path too")
    void everyStreamIsClosed() {
        var provider = ScriptedProvider.toolHang();
        var rig = new Rig().provider(provider).idleTimeout(Duration.ofMillis(120))
                .tools(FakeTool.readOnly("read", "contents"));
        rig.run("hi");

        rig.sink.assertInvariants();
        assertTrue(provider.allStreamsClosed(),
                "close() is the pump's job on every path; a leaked stream is a leaked cancellation listener");
        assertEquals(1, provider.opens(), "a failed turn ends the run; no closing turn is opened");
    }

    @Test
    @DisplayName("16: per-scenario usage accumulates across turns, so a compaction trigger is expressible")
    void usageAccumulatesAcrossTurns() {
        var rig = new Rig().provider(ScriptedProvider.overflowThenStop());
        rig.followUps.push(UserMessage.text("carry on", rig.clock.instant()));
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(ScriptedProvider.OVERFLOW_USAGE.input() + 8, result.state().usage().input(),
                "kon's mock reports a constant usage, so no scenario there can drive compaction at all");
        assertEquals(2, result.state().turnsUsed());
    }

    @Test
    @DisplayName("17: a follow-up request that fails ends the run as Failed — a failed compaction is never a success")
    void aFailedSecondRequestIsReportedAsFailure() {
        var rig = new Rig().provider(ScriptedProvider.compactionFails());
        rig.followUps.push(UserMessage.text("summarise what we have", rig.clock.instant()));
        RunResult result = rig.run("hi");

        rig.sink.assertInvariants();
        var failed = assertInstanceOf(RunOutcome.Failed.class, result.outcome(),
                "kon K-6: the first turn succeeded, but the run did not");
        assertTrue(failed.message().contains("summary request failed"));
        assertEquals(2, rig.sink.ofType(AgentEvent.TurnStart.class).size());
        assertEquals("RunEnd", rig.trace().getLast());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static List<String> kinds(List<AgentMessage> messages) {
        return messages.stream().map(AgentMessage::kind).toList();
    }
}
