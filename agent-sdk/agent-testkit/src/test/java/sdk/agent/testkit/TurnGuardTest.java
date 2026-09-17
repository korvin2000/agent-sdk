package sdk.agent.testkit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sdk.agent.RunLimits;
import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.Thrash;
import sdk.agent.hook.ThrashCounters;
import sdk.agent.hook.TurnContext;
import sdk.agent.hook.TurnGuard;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.json.Json;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ModelRef;
import sdk.agent.message.StopReason;
import sdk.agent.message.Usage;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.ThinkingLevel;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.tool.ToolSpec;

/// Phase 8 of §5: pure verdict tests. Feed [AssistantMessage] sequences, assert the Verdict column
/// of the §4.11.3 table — including the two rules that deviate from both upstreams: `tripped`
/// **zeroes** the other tiers and `healthy` **decays** rather than zeroing.
@DisplayName("TurnGuard — the §4.11.3 table")
final class TurnGuardTest {

    private static final ModelRef MODEL = new ModelRef("test", "scripted", "scripted-1", 200_000, 8_000);
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-01-01T00:00:00Z");

    private final ToolRegistry tools = ToolRegistry.ofTools("test", List.of(
            FakeTool.named("read").kind(ToolKind.READ_ONLY).answering("contents"),
            FakeTool.named("bash").kind(ToolKind.READ_ONLY).answering("total 0"),
            FakeTool.named("alpha").kind(ToolKind.READ_ONLY).answering("a"),
            FakeTool.named("beta").kind(ToolKind.READ_ONLY).answering("b"),
            FakeTool.named("write").validating(FakeTool.objectSchema("path"))));

    private final TurnGuard guard = TurnGuard.defaults();

    private TurnContext ctx(int turnsUsed) { return ctx(turnsUsed, RunLimits.DEFAULTS); }

    private TurnContext ctx(int turnsUsed, RunLimits limits) {
        return new TurnContext("run-1", turnsUsed, List.of(), limits, turnsUsed, tools,
                Cancellation.create(), FakeClock.atEpoch());
    }

    private static AssistantMessage message(StopReason reason, String text, ContentBlock.ToolCall... calls) {
        var content = new ArrayList<ContentBlock>();
        if (!text.isEmpty()) content.add(ContentBlock.Text.of(text));
        content.addAll(List.of(calls));
        return new AssistantMessage(content, MODEL, "r", Usage.EMPTY, reason, null, NOW);
    }

    private static ContentBlock.ToolCall call(String id, String name, Json arguments) {
        return new ContentBlock.ToolCall(id, name, arguments, null);
    }

    private static String repromptOf(TurnVerdict verdict) {
        var retry = assertInstanceOf(TurnVerdict.Retry.class, verdict);
        return ((UserMessage) retry.message()).text();
    }

    // ---- the two absolute checks -----------------------------------------------------------------

    @Test
    @DisplayName("max turns: Stop(MAX_TURNS) with NO message — nothing to say to a model that will not be called")
    void maxTurnsStopsWithoutAMessage() {
        var wantsMoreTools = message(StopReason.TOOL_USE, "",
                new ContentBlock.ToolCall("c1", "read", Json.obj("path", Json.str("a.txt")), null));
        var verdict = assertInstanceOf(TurnVerdict.Stop.class,
                guard.afterAssistant(wantsMoreTools, ctx(3, RunLimits.DEFAULTS.withMaxTurns(3))));

        assertEquals(new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TURNS, "turn cap reached: 3"), verdict.outcome());
        assertNull(verdict.message(), "a fabricated final user turn is a dishonest transcript");
    }

    @Test
    @DisplayName("max turns: a final answer on the last allowed turn completes cleanly")
    void maxTurnsLetsAFinalAnswerThrough() {
        assertInstanceOf(TurnVerdict.Proceed.class,
                guard.afterAssistant(message(StopReason.STOP, "all done"), ctx(3, RunLimits.DEFAULTS.withMaxTurns(3))));
    }

    @Test
    @DisplayName("final turn: beforeRequest returns a COPY with no tools plus a transient instruction")
    void finalTurnStripsToolsAndAddsTheInstruction() {
        var request = new LlmRequest(MODEL, "sys", List.of(UserMessage.text("hi", NOW)),
                tools.specs(), ThinkingLevel.OFF, OptionalInt.empty(), Json.Obj.EMPTY);

        LlmRequest untouched = guard.beforeRequest(request, ctx(1, RunLimits.DEFAULTS.withMaxTurns(5)));
        assertSame(request, untouched, "an ordinary turn is not rewritten at all");

        LlmRequest last = guard.beforeRequest(request, ctx(4, RunLimits.DEFAULTS.withMaxTurns(5)));
        assertAll(
                () -> assertTrue(last.tools().isEmpty(), "the model must not be able to call a tool it cannot finish"),
                () -> assertEquals(2, last.messages().size()),
                () -> assertEquals(TurnGuard.FINAL_TURN_INSTRUCTION, ((UserMessage) last.messages().getLast()).text()),
                () -> assertEquals(List.of(ToolSpec.of(tools.resolve("read").orElseThrow())).getFirst().name(),
                        request.tools().getFirst().name(), "r itself is never mutated"),
                () -> assertEquals(1, request.messages().size(), "the instruction is transient: one request only"));
    }

    // ---- the four reprompt tiers -----------------------------------------------------------------

    @Test
    @DisplayName("EMPTY: Retry(CONTINUE_NUDGE), then Stop(THRASH) at the cap of 2")
    void emptyTurnNudgesThenStops() {
        var empty = message(StopReason.STOP, "");

        assertEquals(TurnGuard.CONTINUE_NUDGE, repromptOf(guard.afterAssistant(empty, ctx(1))));

        var stop = assertInstanceOf(TurnVerdict.Stop.class, guard.afterAssistant(empty, ctx(2)));
        assertEquals(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH, "empty turn cap reached: 2"), stop.outcome());
    }

    @Test
    @DisplayName("TRUNCATED: LENGTH with text and no calls → TRUNCATED_TURN_INSTRUCTION")
    void lengthWithTextIsATruncation() {
        var verdict = guard.afterAssistant(message(StopReason.LENGTH, "I was about to say that the file"), ctx(1));
        assertEquals(TurnGuard.TRUNCATED_TURN_INSTRUCTION, repromptOf(verdict));
        assertEquals("truncated", ((TurnVerdict.Retry) verdict).reason());
    }

    @Test
    @DisplayName("TRUNCATED: TOOL_USE with no call block at all → STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION")
    void toolUseWithNoCallBlockIsATruncationNotAFormatError() {
        String reprompt = repromptOf(guard.afterAssistant(message(StopReason.TOOL_USE, "I will now run"), ctx(1)));

        assertEquals(TurnGuard.STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION.formatted("tool_use"), reprompt);
        assertTrue(reprompt.contains("finish_reason=tool_use"),
                "telling a model that ran out of tokens that its format was wrong makes it reformat, not shorten");
    }

    @Test
    @DisplayName("MALFORMED: an unknown name, Json.Null arguments and a schema rejection all reach the same tier")
    void malformedCoversAllThreeUnusableShapes() {
        String unknown = repromptOf(guard.afterAssistant(
                message(StopReason.TOOL_USE, "", call("c1", "no_such_tool", Json.Obj.EMPTY)), ctx(1)));
        assertAll(
                () -> assertTrue(unknown.startsWith("Your previous response contained a malformed tool call.")),
                () -> assertTrue(unknown.contains("<error>") && unknown.contains("</error>")),
                () -> assertTrue(unknown.contains("there is no tool named `no_such_tool`")),
                () -> assertTrue(unknown.contains("Available tools: read, bash, alpha, beta, write")));

        var freshGuard = TurnGuard.defaults();
        String nullArgs = repromptOf(freshGuard.afterAssistant(
                message(StopReason.TOOL_USE, "", call("c1", "read", Json.Null.NULL)), ctx(1)));
        assertTrue(nullArgs.contains("the arguments were not valid JSON"),
                "a native tool call with garbage arguments is invisible to nanocoder's XML-only counter");

        var schemaGuard = TurnGuard.defaults();
        String rejected = repromptOf(schemaGuard.afterAssistant(
                message(StopReason.TOOL_USE, "", call("c1", "write", Json.obj("other", Json.str("x")))), ctx(1)));
        assertAll(
                () -> assertTrue(rejected.contains("Validation failed for tool \"write\"")),
                () -> assertTrue(rejected.contains("Expected input schema for `write`:")),
                () -> assertTrue(rejected.contains(FakeTool.objectSchema("path").toPrettyText()),
                        "the schema itself goes back, or the model has nothing to correct against"),
                () -> assertTrue(rejected.endsWith("Please try again using the correct format.")));
    }

    @Test
    @DisplayName("a turn with ONE usable call among unusable ones is not malformed — the funnel reports the rest")
    void oneUsableCallIsEnoughToProceed() {
        var verdict = guard.afterAssistant(message(StopReason.TOOL_USE, "",
                call("c1", "no_such_tool", Json.Obj.EMPTY),
                call("c2", "read", Json.obj("path", Json.str("a.txt")))), ctx(1));

        assertInstanceOf(TurnVerdict.Proceed.class, verdict);
    }

    // ---- the three window tiers ------------------------------------------------------------------

    @Test
    @DisplayName("REPEATED: the same batch on 3 consecutive turns → Stop(THRASH) with LOOP_DETECTED")
    void identicalBatchesThreeTimesStops() {
        var batch = message(StopReason.TOOL_USE, "", call("c1", "read", Json.obj("path", Json.str("a.txt"))));

        assertInstanceOf(TurnVerdict.Proceed.class, guard.afterAssistant(batch, ctx(1)));
        assertInstanceOf(TurnVerdict.Proceed.class, guard.afterAssistant(batch, ctx(2)));

        var stop = assertInstanceOf(TurnVerdict.Stop.class, guard.afterAssistant(batch, ctx(3)));
        assertEquals(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH, "identical tool batch on 3 consecutive turns"),
                stop.outcome());
        assertEquals(TurnGuard.LOOP_DETECTED.formatted("read"), ((UserMessage) stop.message()).text());
    }

    @Test
    @DisplayName("SAME_TOOL: five consecutive calls to one tool, all with different arguments → Stop(THRASH)")
    void sameToolFiveTimesStops() {
        for (int i = 1; i <= 4; i++) {
            var m = message(StopReason.TOOL_USE, "", call("c" + i, "read", Json.obj("path", Json.str("file" + i))));
            assertInstanceOf(TurnVerdict.Proceed.class, guard.afterAssistant(m, ctx(i)),
                    "different arguments must not trip the identical-batch tier");
        }
        var fifth = message(StopReason.TOOL_USE, "", call("c5", "read", Json.obj("path", Json.str("file5"))));
        var stop = assertInstanceOf(TurnVerdict.Stop.class, guard.afterAssistant(fifth, ctx(5)));
        assertEquals(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH, "same tool on 5 consecutive calls"),
                stop.outcome());
    }

    @Test
    @DisplayName("DOMINANT: one signature 8 of the last 10 calls → Retry with a warning, never Stop")
    void dominantSignatureRetriesRatherThanStopping() {
        // Eight reads of the SAME file among ten calls, arranged so neither the identical-batch nor
        // the same-tool tier fires first: counting tool NAMES here would abort eight reads of eight
        // different files, the normal opening of any code task.
        Json same = Json.obj("path", Json.str("hot.txt"));
        var first = message(StopReason.TOOL_USE, "",
                call("a1", "alpha", same), call("a2", "alpha", same), call("a3", "alpha", same), call("a4", "alpha", same),
                call("b1", "beta", Json.obj("n", Json.str("1"))));
        assertInstanceOf(TurnVerdict.Proceed.class, guard.afterAssistant(first, ctx(1)));

        var second = message(StopReason.TOOL_USE, "",
                call("a5", "alpha", same), call("a6", "alpha", same), call("a7", "alpha", same), call("a8", "alpha", same),
                call("b2", "beta", Json.obj("n", Json.str("2"))));
        var verdict = guard.afterAssistant(second, ctx(2));

        var retry = assertInstanceOf(TurnVerdict.Retry.class, verdict);
        assertEquals("dominant tool call signature", retry.reason());
        assertEquals(TurnGuard.LOOP_DETECTED.formatted("alpha"), ((UserMessage) retry.message()).text());
    }

    // ---- the two counter rules -------------------------------------------------------------------

    @Test
    @DisplayName("decay, not zeroing: alternating malformed/valid turns never trip a cap of 2 (mini-swe SWE-5)")
    void alternatingMalformedAndValidNeverTrips() {
        var malformed = message(StopReason.TOOL_USE, "", call("bad", "no_such_tool", Json.Obj.EMPTY));
        String[] names = {"read", "bash"};

        for (int cycle = 0; cycle < 4; cycle++) {
            assertInstanceOf(TurnVerdict.Retry.class, guard.afterAssistant(malformed, ctx(cycle * 2 + 1)),
                    "cycle " + cycle + ": a malformed turn is refused, never fatal on its own");
            var valid = message(StopReason.TOOL_USE, "",
                    call("ok" + cycle, names[cycle % 2], Json.obj("path", Json.str("f" + cycle))));
            assertInstanceOf(TurnVerdict.Proceed.class, guard.afterAssistant(valid, ctx(cycle * 2 + 2)));
        }
    }

    @Test
    @DisplayName("zeroing on a different failure: alternating distinct failures never trip (nanocoder's cross-contamination fix)")
    void alternatingDistinctFailuresNeverTrip() {
        var empty = message(StopReason.STOP, "");
        var truncated = message(StopReason.LENGTH, "cut off here");

        for (int cycle = 0; cycle < 4; cycle++) {
            assertEquals(TurnGuard.CONTINUE_NUDGE, repromptOf(guard.afterAssistant(empty, ctx(cycle * 2 + 1))));
            assertEquals(TurnGuard.TRUNCATED_TURN_INSTRUCTION, repromptOf(guard.afterAssistant(truncated, ctx(cycle * 2 + 2))));
        }
    }

    @Test
    @DisplayName("ThrashCounters: the N-th failing turn of a kind is refused; caps are validated by name")
    void thrashCountersSemantics() {
        var counters = new ThrashCounters(ThrashCounters.defaultCaps());
        assertTrue(!counters.tripped(Thrash.EMPTY));
        assertTrue(counters.tripped(Thrash.EMPTY), "cap 2 means the second consecutive empty turn is refused");

        var mixed = new ThrashCounters(ThrashCounters.defaultCaps());
        assertTrue(!mixed.tripped(Thrash.EMPTY));
        assertTrue(!mixed.tripped(Thrash.MALFORMED), "a different failure mode zeroes the others");
        assertEquals(0, mixed.count(Thrash.EMPTY));
        assertTrue(!mixed.tripped(Thrash.EMPTY));

        var decaying = new ThrashCounters(ThrashCounters.defaultCaps());
        assertTrue(!decaying.tripped(Thrash.MALFORMED));
        decaying.healthy();
        assertEquals(0, decaying.count(Thrash.MALFORMED), "a healthy turn decays by one, it does not reset the budget");

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ThrashCounters(Map.of()));
        assertTrue(thrown.getMessage().startsWith("cap for "), "an empty map must name the missing tier, not die inside EnumMap");
    }

    @Test
    @DisplayName("the per-run window is dropped at RunEnd, so one guard serves many runs")
    void runStateIsReleasedAtRunEnd() {
        var batch = message(StopReason.TOOL_USE, "", call("c1", "read", Json.obj("path", Json.str("a.txt"))));
        guard.afterAssistant(batch, ctx(1));
        guard.afterAssistant(batch, ctx(2));
        guard.onEvent(new sdk.agent.event.AgentEvent.RunEnd("run-1", -1, NOW, List.of(), new RunOutcome.Aborted()));

        assertInstanceOf(TurnVerdict.Proceed.class, guard.afterAssistant(batch, ctx(3)),
                "the streak belongs to the run that made it");
    }
}
