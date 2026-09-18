package sdk.agent.testkit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sdk.agent.event.AgentEvent;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ModelRef;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.provider.ThinkingLevel;
import sdk.agent.tool.ToolMessages;
import sdk.agent.tool.ToolSpec;
import sdk.agent.turn.Need;
import sdk.agent.turn.StepInput;
import sdk.agent.turn.StepOutcome;
import sdk.agent.turn.TurnMachine;
import sdk.agent.turn.TurnPhase;
import sdk.agent.turn.TurnState;

/// The turn machine driven entirely by hand-written [StepInput] sequences. No
/// provider, no filesystem, no threads and no clock — `step` takes the instant explicitly — so
/// every claim about ordering, padding and argument assembly is checkable before a thread exists.
@DisplayName("TurnMachine (pure, no I/O)")
final class TurnMachineTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "scripted-1", 200_000, 8_000);
    private static final LlmRequest REQUEST =
            new LlmRequest(MODEL, "sys", List.of(), List.of(), ThinkingLevel.OFF, OptionalInt.empty(), Json.Obj.EMPTY);

    private final TurnMachine machine = new TurnMachine(Duration.ofMillis(200));
    private final List<AgentEvent> events = new ArrayList<>();

    private TurnState opening() { return TurnState.opening("run-1", 0, MODEL); }

    private TurnState drive(TurnState state, StepInput... inputs) {
        TurnState current = state;
        for (StepInput in : inputs) {
            StepOutcome out = machine.step(current, in, NOW);
            events.addAll(out.events());
            current = out.state();
        }
        return current;
    }

    private static StepInput chunk(LlmStreamEvent e) { return new StepInput.Chunk(e); }

    private static List<String> trace(List<AgentEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList();
    }

    // ---- totality ------------------------------------------------------------------------------

    @Test
    @DisplayName("every phase-illegal input yields Ignored with a diagnostic and an unchanged state")
    void phaseIllegalInputsAreIgnored() {
        TurnState opening = opening();
        StepOutcome a = machine.step(opening, new StepInput.StreamOpened(), NOW);
        assertInstanceOf(StepOutcome.Ignored.class, a);
        assertSame(opening, a.state(), "an ignored input must not change the state");
        assertEquals("StreamOpened is not valid in phase OPENING", ((StepOutcome.Ignored) a).reason());
        assertTrue(a.events().isEmpty(), "an ignored input emits nothing");

        TurnState requested = drive(opening, new StepInput.Begin(REQUEST));
        assertEquals(TurnPhase.STREAM_REQUESTED, requested.phase());
        assertInstanceOf(StepOutcome.Ignored.class, machine.step(requested, chunk(new LlmStreamEvent.Start()), NOW));

        TurnState streaming = drive(requested, new StepInput.StreamOpened());
        assertEquals(TurnPhase.STREAMING, streaming.phase());
        assertInstanceOf(StepOutcome.Ignored.class, machine.step(streaming, new StepInput.Begin(REQUEST), NOW));
        assertInstanceOf(StepOutcome.Ignored.class, machine.step(streaming, new StepInput.Verdict(TurnVerdict.PROCEED), NOW));

        TurnState ready = drive(streaming, chunk(new LlmStreamEvent.Done(StopReason.STOP, Usage.EMPTY, "r")));
        assertEquals(TurnPhase.ASSISTANT_READY, ready.phase());
        assertInstanceOf(StepOutcome.Ignored.class, machine.step(ready, chunk(new LlmStreamEvent.Start()), NOW));
        assertInstanceOf(StepOutcome.Ignored.class, machine.step(ready, new StepInput.StreamOpened(), NOW));

        TurnState closed = drive(ready, new StepInput.Verdict(TurnVerdict.PROCEED));
        assertEquals(TurnPhase.CLOSED, closed.phase());
        assertInstanceOf(StepOutcome.Ignored.class, machine.step(closed, new StepInput.Verdict(TurnVerdict.PROCEED), NOW));
        assertInstanceOf(StepOutcome.Ignored.class, machine.step(closed, new StepInput.Cancel(), NOW),
                "Cancel on a closed turn is a no-op, not a second TurnEnd");
    }

    @Test
    @DisplayName("Begin captures the advertised tools as the turn's execution authority")
    void beginCapturesTheAdvertisedTools() {
        var read = FakeTool.readOnly("read", "contents");
        TurnState requested = drive(opening(), new StepInput.Begin(REQUEST.withTools(List.of(ToolSpec.of(read)))));
        assertEquals(Set.of("read"), requested.allowedTools());
        assertEquals(Set.of(), opening().allowedTools(), "nothing is allowed before a request exists");
    }

    // ---- argument assembly (Appendix B, 10 / 11 / 12) --------------------------------------------

    @Test
    @DisplayName("12: a stall is a failed turn; malformed fragments never fall back to the stale snapshot")
    void scenario12StallIsAFailedTurnAndTheSnapshotIsNeverUsed() {
        TurnState state = drive(opening(),
                new StepInput.Begin(REQUEST),
                new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "write",
                        Json.obj("path", Json.str("/tmp/stale.txt"), "content", Json.str("stale snapshot")))),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "{\"path\": \"/tmp/fresh.txt\", \"content\": \"replace", true)),
                new StepInput.IdleTimedOut());

        ContentBlock.ToolCall call = state.assistant().toolCalls().getFirst();
        assertAll(
                () -> assertEquals(StopReason.ERROR, state.assistant().stopReason(), "a stall ends the turn as an error"),
                () -> assertEquals(TurnMachine.STALLED, state.assistant().errorMessage()),
                () -> assertSame(Json.Null.NULL, call.arguments(), "the truncated call must carry Json.Null, never the stale snapshot"),
                () -> assertEquals(1, state.slots().size(), "one slot per call, sized when the assistant is final"));
    }

    @Test
    @DisplayName("11: unparseable fragments become Json.Null, with or without a start snapshot")
    void scenario11UnparseableFragmentsAreNull() {
        TurnState noSnapshot = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "write", Json.Obj.EMPTY)),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "{\"path\": \"/tmp/test.txt\", \"content\": \"incomplete", false)),
                chunk(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.EMPTY, "r")));
        assertSame(Json.Null.NULL, noSnapshot.assistant().toolCalls().getFirst().arguments());

        TurnState withSnapshot = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "write", Json.obj("path", Json.str("/tmp/snap.txt")))),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "{\"path\": \"/tmp/tru", false)),
                chunk(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.EMPTY, "r")));
        assertSame(Json.Null.NULL, withSnapshot.assistant().toolCalls().getFirst().arguments(),
                "received fragments are authoritative; a snapshot never masks a broken delta stream");
    }

    @Test
    @DisplayName("a call with no fragments at all uses the start snapshot (a provider that sends arguments whole)")
    void noFragmentsUseTheSnapshot() {
        TurnState state = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "read", Json.obj("path", Json.str("whole.txt")))),
                chunk(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.EMPTY, "r")));

        assertEquals(Json.obj("path", Json.str("whole.txt")), state.assistant().toolCalls().getFirst().arguments());
    }

    @Test
    @DisplayName("10: a stall with valid JSON keeps the call for diagnosis but Proceed runs nothing")
    void scenario10StallWithValidJsonNeverExecutes() {
        TurnState state = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "read", Json.Obj.EMPTY)),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "{\"path\":\"file.txt\"}", false)),
                new StepInput.IdleTimedOut());

        assertTrue(state.assistant().terminal());
        assertEquals(Json.obj("path", Json.str("file.txt")), state.assistant().toolCalls().getFirst().arguments());

        StepOutcome out = machine.step(state, new StepInput.Verdict(TurnVerdict.PROCEED), NOW);
        assertInstanceOf(StepOutcome.Finished.class, out, "a failed turn closes instead of requesting tools");
        assertEquals(ToolMessages.NOT_EXECUTED, ((AgentEvent.TurnEnd) out.events().getLast()).toolResults().getFirst().text());
    }

    @Test
    @DisplayName("the idle timeout is offered only while a tool call is being assembled")
    void idleTimeoutOnlyWhileACallIsOpen() {
        TurnState streaming = drive(opening(), new StepInput.Begin(REQUEST), new StepInput.StreamOpened());
        StepOutcome text = machine.step(streaming, chunk(new LlmStreamEvent.TextDelta(0, "thinking hard")), NOW);
        assertEquals(new Need.Chunk(java.util.OptionalLong.empty()), ((StepOutcome.Needs) text).need(),
                "a long text block must not be killed by a tool-call idle timer");

        StepOutcome call = machine.step(text.state(),
                chunk(new LlmStreamEvent.ToolCallStart(1, "call-1", "read", Json.Obj.EMPTY)), NOW);
        assertEquals(new Need.Chunk(java.util.OptionalLong.of(200)), ((StepOutcome.Needs) call).need());
    }

    // ---- block finalisation (Appendix B, 14 / 15 / 18) -----------------------------------------

    @Test
    @DisplayName("14/15: whitespace-only text never opens a TEXT block")
    void whitespaceOnlyTextNeverOpensABlock() {
        TurnState thenThink = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.TextStart(0)),
                chunk(new LlmStreamEvent.TextDelta(0, "\n\n")),
                chunk(new LlmStreamEvent.TextEnd(0, "\n\n", null)),
                chunk(new LlmStreamEvent.ThinkingStart(1)),
                chunk(new LlmStreamEvent.ThinkingDelta(1, "Right.")),
                chunk(new LlmStreamEvent.ThinkingEnd(1, "Right.", "sig", false)),
                chunk(new LlmStreamEvent.TextStart(2)),
                chunk(new LlmStreamEvent.TextDelta(2, "Hello, world!")),
                chunk(new LlmStreamEvent.TextEnd(2, "Hello, world!", null)),
                chunk(new LlmStreamEvent.Done(StopReason.STOP, Usage.EMPTY, "r")));

        assertEquals(List.of(new ContentBlock.Thinking("Right.", "sig", false),
                             new ContentBlock.Text("Hello, world!", null)),
                thenThink.assistant().content(),
                "the blank block must be dropped and the order must be THINK then TEXT");
        assertTrue(thenThink.content().isEmpty(), "the accumulator is empty once the assistant is final");

        events.clear();
        TurnState thenText = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.TextStart(0)),
                chunk(new LlmStreamEvent.TextDelta(0, "   \t\n ")),
                chunk(new LlmStreamEvent.TextEnd(0, "   \t\n ", null)),
                chunk(new LlmStreamEvent.TextStart(1)),
                chunk(new LlmStreamEvent.TextDelta(1, "Hello, world!")),
                chunk(new LlmStreamEvent.TextEnd(1, "Hello, world!", null)),
                chunk(new LlmStreamEvent.Done(StopReason.STOP, Usage.EMPTY, "r")));

        assertEquals(List.of(new ContentBlock.Text("Hello, world!", null)), thenText.assistant().content());
    }

    @Test
    @DisplayName("18: a content-block transition does not flush the argument accumulator (kon K-2)")
    void interleavedTextDoesNotFlushTheArgumentAccumulator() {
        TurnState state = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "write", Json.Obj.EMPTY)),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "{\"path\":\"/tmp/x.txt\",", false)),
                chunk(new LlmStreamEvent.TextStart(1)),
                chunk(new LlmStreamEvent.TextDelta(1, "writing it now")),
                chunk(new LlmStreamEvent.TextEnd(1, "writing it now", null)),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "\"content\":\"hi\"}", false)),
                chunk(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.EMPTY, "r")));

        assertEquals(Json.obj("path", Json.str("/tmp/x.txt"), "content", Json.str("hi")),
                state.assistant().toolCalls().getFirst().arguments());
    }

    @Test
    @DisplayName("Done finalises exactly one MessageEnd, and the transcript sees only the final message")
    void doneFinalisesExactlyOneMessageEnd() {
        TurnState state = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.Start()),
                chunk(new LlmStreamEvent.TextStart(0)),
                chunk(new LlmStreamEvent.TextDelta(0, "Hello, ")),
                chunk(new LlmStreamEvent.TextDelta(0, "world!")),
                chunk(new LlmStreamEvent.TextEnd(0, "Hello, world!", null)),
                chunk(new LlmStreamEvent.Done(StopReason.STOP, Usage.tokens(10, 5), "resp-1")));

        assertEquals(List.of("TurnStart", "MessageStart", "MessageUpdate", "MessageUpdate", "MessageUpdate",
                             "MessageUpdate", "MessageEnd"), trace(events),
                "Start emits no update; each of the four text events emits exactly one");
        assertEquals(1, events.stream().filter(AgentEvent.MessageEnd.class::isInstance).count());
        assertEquals("Hello, world!", state.assistant().text());
        assertEquals(Usage.tokens(10, 5), state.assistant().usage());
        assertEquals("resp-1", state.assistant().responseId());
    }

    @Test
    @DisplayName("a stream that ends with no terminal event still finalises one assistant message")
    void streamExhaustedFinalisesTheTurn() {
        TurnState state = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.TextStart(0)),
                chunk(new LlmStreamEvent.TextDelta(0, "half a sen")),
                new StepInput.StreamExhausted());

        assertEquals(StopReason.ERROR, state.assistant().stopReason());
        assertEquals("The provider stream ended without a terminal event.", state.assistant().errorMessage());
        assertEquals("half a sen", state.assistant().text());
        assertEquals(1, events.stream().filter(AgentEvent.MessageEnd.class::isInstance).count());
    }

    // ---- tools and cancellation ----------------------------------------------------------------

    @Test
    @DisplayName("Cancel in TOOLS_RUNNING pads unfilled slots and emits exactly one index-aligned TurnEnd")
    void cancelInToolsRunningPadsUnfilledSlots() {
        TurnState running = twoCallTurnInToolsRunning();
        events.clear();

        ToolResultMessage first = result("call-1", "read", "file contents");
        TurnState settled = drive(running, new StepInput.ToolSettled("call-1", first));
        assertEquals(TurnPhase.TOOLS_RUNNING, settled.phase(), "one of two settled leaves the turn running");

        StepOutcome out = machine.step(settled, new StepInput.Cancel(), NOW);
        events.addAll(out.events());

        assertInstanceOf(StepOutcome.Finished.class, out);
        assertEquals(List.of("MessageStart", "MessageEnd", "TurnEnd"), trace(events),
                "the pad is emitted as a message pair, then the single TurnEnd");

        var turnEnd = (AgentEvent.TurnEnd) events.getLast();
        assertEquals(2, turnEnd.toolResults().size(), "I10: one result per call");
        assertEquals("call-1", turnEnd.toolResults().get(0).toolCallId());
        assertEquals("call-2", turnEnd.toolResults().get(1).toolCallId());
        assertEquals("file contents", turnEnd.toolResults().get(0).text());
        assertEquals(ToolMessages.NOT_EXECUTED, turnEnd.toolResults().get(1).text());
        assertTrue(turnEnd.toolResults().get(1).isError(), "a padded slot is an error result");
        assertEquals(TurnPhase.CLOSED, out.state().phase());
    }

    @Test
    @DisplayName("ToolSettled twice for one id is Ignored — a replayed result is a no-op (I12)")
    void toolSettledTwiceIsIgnored() {
        TurnState running = twoCallTurnInToolsRunning();
        TurnState once = drive(running, new StepInput.ToolSettled("call-1", result("call-1", "read", "first")));

        StepOutcome again = machine.step(once, new StepInput.ToolSettled("call-1", result("call-1", "read", "second")), NOW);
        assertInstanceOf(StepOutcome.Ignored.class, again);
        assertEquals("ToolSettled twice for call-1", ((StepOutcome.Ignored) again).reason());
        assertSame(once, again.state());
        assertTrue(again.events().isEmpty());

        StepOutcome unknown = machine.step(once, new StepInput.ToolSettled("call-9", result("call-9", "read", "x")), NOW);
        assertEquals("ToolSettled for unknown tool call id call-9", ((StepOutcome.Ignored) unknown).reason());
    }

    @Test
    @DisplayName("Stop and Retry pad every call before TurnEnd — a refused turn still owes one result per call")
    void stopAndRetryPadEveryCall() {
        TurnState ready = twoCallTurnReady();

        StepOutcome stop = machine.step(ready, new StepInput.Verdict(new TurnVerdict.Stop(new sdk.agent.event.RunOutcome.Aborted(), null)), NOW);
        assertInstanceOf(StepOutcome.Finished.class, stop);
        assertEquals(List.of("MessageStart", "MessageEnd", "MessageStart", "MessageEnd", "TurnEnd"), trace(stop.events()));
        var turnEnd = (AgentEvent.TurnEnd) stop.events().getLast();
        assertEquals(List.of("call-1", "call-2"), turnEnd.toolResults().stream().map(ToolResultMessage::toolCallId).toList());
        assertTrue(turnEnd.toolResults().stream().allMatch(r -> r.isError() && r.text().equals(ToolMessages.NOT_EXECUTED)));

        StepOutcome retry = machine.step(ready, new StepInput.Verdict(
                new TurnVerdict.Retry(sdk.agent.message.UserMessage.text("retry", NOW), "policy")), NOW);
        assertEquals(2, ((AgentEvent.TurnEnd) retry.events().getLast()).toolResults().size());
    }

    @Test
    @DisplayName("Proceed with no tool calls closes the turn immediately")
    void proceedWithNoCallsClosesTheTurn() {
        TurnState ready = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.TextStart(0)),
                chunk(new LlmStreamEvent.TextDelta(0, "done")),
                chunk(new LlmStreamEvent.TextEnd(0, "done", null)),
                chunk(new LlmStreamEvent.Done(StopReason.STOP, Usage.EMPTY, "r")));
        events.clear();

        StepOutcome out = machine.step(ready, new StepInput.Verdict(TurnVerdict.PROCEED), NOW);
        assertInstanceOf(StepOutcome.Finished.class, out);
        assertEquals(List.of("TurnEnd"), trace(out.events()));
        assertTrue(((AgentEvent.TurnEnd) out.events().getFirst()).toolResults().isEmpty());
    }

    @Test
    @DisplayName("a failed turn that collected calls never requests tools, whatever the verdict")
    void aFailedTurnNeverRequestsTools() {
        TurnState failed = drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "write", Json.Obj.EMPTY)),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "{\"path\":\"secret.txt\"}", false)),
                chunk(new LlmStreamEvent.Failed(StopReason.ERROR, "connection reset")));

        StepOutcome out = machine.step(failed, new StepInput.Verdict(TurnVerdict.PROCEED), NOW);
        assertInstanceOf(StepOutcome.Finished.class, out);
        assertEquals(1, ((AgentEvent.TurnEnd) out.events().getLast()).toolResults().size());
    }

    @Test
    @DisplayName("Cancel before the assistant is final produces an ABORTED assistant message and closes the turn")
    void cancelWhileStreamingAbortsTheAssistant() {
        TurnState streaming = drive(opening(), new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.TextStart(0)),
                chunk(new LlmStreamEvent.TextDelta(0, "partial")));
        events.clear();

        StepOutcome out = machine.step(streaming, new StepInput.Cancel(), NOW);
        assertEquals(List.of("MessageEnd", "TurnEnd"), trace(out.events()));
        assertEquals(TurnPhase.CLOSED, out.state().phase());
        assertEquals(StopReason.ABORTED, out.state().assistant().stopReason());
        assertEquals("The run was aborted.", out.state().assistant().errorMessage());
        assertEquals("partial", out.state().assistant().text(), "the partial text is kept, once, at MessageEnd");
        assertNull(out.state().assistant().responseId());
    }

    @Test
    @DisplayName("Cancel before the stream opens still brackets the turn: TurnStart, MessageStart, MessageEnd, TurnEnd")
    void cancelBeforeTheStreamOpensStillBracketsTheTurn() {
        StepOutcome fromOpening = machine.step(opening(), new StepInput.Cancel(), NOW);
        assertEquals(List.of("TurnStart", "MessageStart", "MessageEnd", "TurnEnd"), trace(fromOpening.events()),
                "I3 and I8 must hold on the abort path too, even when nothing was ever emitted");
        assertEquals(StopReason.ABORTED, fromOpening.state().assistant().stopReason());

        TurnState requested = drive(opening(), new StepInput.Begin(REQUEST));
        events.clear();
        StepOutcome fromRequested = machine.step(requested, new StepInput.Cancel(), NOW);
        assertEquals(List.of("MessageStart", "MessageEnd", "TurnEnd"), trace(fromRequested.events()),
                "TurnStart was already emitted by Begin; the message pair is still owed");
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private TurnState twoCallTurnReady() {
        return drive(opening(),
                new StepInput.Begin(REQUEST), new StepInput.StreamOpened(),
                chunk(new LlmStreamEvent.ToolCallStart(0, "call-1", "read", Json.Obj.EMPTY)),
                chunk(new LlmStreamEvent.ToolCallDelta(0, "{\"path\":\"a.txt\"}", false)),
                chunk(new LlmStreamEvent.ToolCallStart(1, "call-2", "read", Json.Obj.EMPTY)),
                chunk(new LlmStreamEvent.ToolCallDelta(1, "{\"path\":\"b.txt\"}", false)),
                chunk(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.EMPTY, "r")));
    }

    private TurnState twoCallTurnInToolsRunning() {
        TurnState ready = twoCallTurnReady();
        StepOutcome out = machine.step(ready, new StepInput.Verdict(TurnVerdict.PROCEED), NOW);
        assertInstanceOf(StepOutcome.Needs.class, out);
        assertEquals(new Need.Tools(ready.assistant().toolCalls()), ((StepOutcome.Needs) out).need());
        return out.state();
    }

    private static ToolResultMessage result(String id, String tool, String text) {
        return new ToolResultMessage(id, tool, List.of(ContentBlock.Text.of(text)), Json.Null.NULL, false, NOW);
    }
}
