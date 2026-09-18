package sdk.agent.testkit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.AgentEvent;
import sdk.agent.hook.AfterToolCall;
import sdk.agent.hook.AgentHooks;
import sdk.agent.hook.BeforeToolCall;
import sdk.agent.hook.ToolDecision;
import sdk.agent.hook.ToolOverride;
import sdk.agent.json.Json;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolMessages;
import sdk.agent.tool.ToolResult;

/// Drives preparation, permission, execution, and output-filter failures through the real engine.
/// Every announced call must have one ToolEnd and one transcript result, without leaking output
/// when policy fails. Rig deliberately has no optional TurnGuard.
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("ToolFunnel — the ten rows")
final class ToolFunnelTableTest {

    private static ScriptedProvider calling(String tool, String argumentsJson) {
        var steps = new ArrayList<ScriptedProvider.Step>();
        steps.add(ScriptedProvider.emit(new LlmStreamEvent.Start()));
        steps.addAll(ScriptedProvider.toolCall(0, "call-1", tool, argumentsJson));
        steps.add(ScriptedProvider.emit(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.tokens(10, 5), "resp-1", sdk.agent.json.Json.nil())));
        return ScriptedProvider.of(steps);
    }

    private static ScriptedProvider callingThenHanging(String tool, String argumentsJson) {
        var steps = new ArrayList<ScriptedProvider.Step>();
        steps.add(ScriptedProvider.emit(new LlmStreamEvent.Start()));
        steps.addAll(ScriptedProvider.toolCall(0, "call-1", tool, argumentsJson));
        steps.add(ScriptedProvider.hang());
        return ScriptedProvider.of(steps);
    }

    private static ToolResultMessage resultOf(Rig rig) {
        return rig.sink.toolResult("call-1").orElseThrow(() -> new AssertionError("no tool result: " + rig.trace()));
    }

    private static ErrorKind kindOf(Rig rig) {
        AgentEvent.ToolEnd end = rig.sink.ofType(AgentEvent.ToolEnd.class).getFirst();
        return assertInstanceOf(ToolResult.Err.class, end.result()).kind();
    }

    private static void assertFunnelExitedOnce(Rig rig) {
        rig.sink.assertInvariants();
        assertAll(
                () -> assertEquals(1, rig.sink.ofType(AgentEvent.ToolStart.class).size(), "I5: one ToolStart"),
                () -> assertEquals(1, rig.sink.ofType(AgentEvent.ToolEnd.class).size(), "I5: exactly one ToolEnd"),
                () -> assertTrue(rig.sink.toolResult("call-1").isPresent(), "I6: a transcript entry for the call"));
    }

    // ---- row 1 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("unknown tool → TOOL_NOT_FOUND, `Tool {name} not found` (pi agent-loop.ts:483)")
    void unknownTool() {
        var rig = new Rig().provider(ScriptedProvider.unknownTool());
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals("Tool unknown_tool not found", resultOf(rig).text());
        assertEquals(ErrorKind.TOOL_NOT_FOUND, kindOf(rig));
        assertTrue(resultOf(rig).isError());
    }

    // ---- row 2 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("prepareArguments throws → INVALID_ARGUMENTS carrying the exception message")
    void prepareArgumentsThrows() {
        var edit = FakeTool.named("edit").preparing(_ -> {
            throw new IllegalArgumentException("cannot lift {oldText,newText} into edits[]");
        });
        var rig = new Rig().provider(calling("edit", "{\"oldText\":\"a\",\"newText\":\"b\"}")).tools(edit);
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.INVALID_ARGUMENTS, kindOf(rig));
        assertTrue(resultOf(rig).text().contains("cannot lift {oldText,newText} into edits[]"),
                "the shim's own message must reach the model, was: " + resultOf(rig).text());
        assertEquals(0, edit.calls());
    }


    @Test
    @DisplayName("stalled malformed arguments never reach execution preparation")
    void unparseableArgumentsAfterAStall() {
        var write = FakeTool.mutating("write", "written");
        var rig = new Rig().provider(ScriptedProvider.toolHangInvalidJson())
                .idleTimeout(Duration.ofMillis(120)).tools(write);
        rig.run("hi");

        rig.sink.assertInvariants();
        assertTrue(rig.sink.ofType(AgentEvent.ToolStart.class).isEmpty());
        assertTrue(resultOf(rig).isError());
        assertEquals(0, write.calls());
    }

    @Test
    @DisplayName("malformed successful tool arguments fail without execution")
    void unparseableArgumentsWithoutASnapshot() {
        var write = FakeTool.mutating("write", "written");
        var rig = new Rig().provider(calling("write", "{\"path\": \"/tmp/x\", \"content\": \"incomp")).tools(write);
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.INVALID_ARGUMENTS, kindOf(rig));
        assertEquals(0, write.calls());
    }

    @Test
    @DisplayName("a stalled turn cannot execute even complete arguments")
    void validationFailsAfterAStall() {
        var write = FakeTool.named("write").validating(FakeTool.objectSchema("path"));
        var rig = new Rig().provider(callingThenHanging("write", "{\"unexpected\":\"x\"}"))
                .idleTimeout(Duration.ofMillis(120)).tools(write);
        rig.run("hi");

        rig.sink.assertInvariants();
        assertTrue(rig.sink.ofType(AgentEvent.ToolStart.class).isEmpty());
        assertTrue(resultOf(rig).isError());
        assertEquals(0, write.calls());
    }

    // ---- rows 7, 8: the permissions seam ---------------------------------------------------------

    @Test
    @DisplayName("beforeToolCall blocks with a reason → BLOCKED carrying the reason")
    void beforeToolCallBlocksWithAReason() {
        var bash = FakeTool.mutating("bash", "total 0");
        var rig = new Rig().provider(calling("bash", "{\"command\":\"rm -rf /\"}")).tools(bash)
                .hooks(new AgentHooks() {
                    @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation cancel) {
                        return ToolDecision.block("the user denied `rm -rf /`");
                    }
                });
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.BLOCKED, kindOf(rig));
        assertEquals("the user denied `rm -rf /`", resultOf(rig).text());
        assertEquals(0, bash.calls(), "a veto has no side effect to undo");
    }


    @Test
    @DisplayName("beforeToolCall throws → HOOK_FAILED, and the call still leaves through the one exit")
    void beforeToolCallThrows() {
        var bash = FakeTool.mutating("bash", "total 0");
        var rig = new Rig().provider(calling("bash", "{\"command\":\"ls\"}")).tools(bash)
                .hooks(new AgentHooks() {
                    @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation cancel) {
                        throw new IllegalStateException("permission service unreachable");
                    }
                });
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.HOOK_FAILED, kindOf(rig));
        assertTrue(resultOf(rig).text().contains("permission service unreachable"));
        assertEquals(0, bash.calls());
    }

    @Test
    @DisplayName("beforeToolCall may narrow the arguments, and the tool sees the rewrite")
    void beforeToolCallRewritesArguments() {
        var bash = FakeTool.named("bash").doing(call -> ToolResult.text("ran " + call.rawArguments().toText()));
        var rig = new Rig().provider(calling("bash", "{\"command\":\"ls -la /\"}")).tools(bash)
                .hooks(new AgentHooks() {
                    @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation cancel) {
                        return ToolDecision.allow(Json.obj("command", Json.str("ls")));
                    }
                });
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(1, bash.calls());
        assertEquals("ran {\"command\":\"ls\"}", resultOf(rig).text());
    }

    // ---- rows 9, 10: what the tool itself did ----------------------------------------------------

    @Test
    @DisplayName("a tool that reports failure as data keeps TOOL_REPORTED and its details")
    void toolReportsFailureAsData() {
        Json details = Json.obj("exitCode", Json.num(1), "file", Json.str("Main.java"));
        var compile = FakeTool.named("compile").reporting("Main.java:3: error: ';' expected", details);
        var rig = new Rig().provider(calling("compile", "{}")).tools(compile);
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.TOOL_REPORTED, kindOf(rig),
                "a failed compile is the answer, not a crash — EXECUTION_FAILED would be a lie");
        assertEquals("Main.java:3: error: ';' expected", resultOf(rig).text());
        assertEquals(details, resultOf(rig).details(), "details survive on the failure path");
        assertTrue(resultOf(rig).isError());
    }

    @Test
    @DisplayName("a tool that throws → EXECUTION_FAILED, described, with I5/I6 intact")
    void toolThrows() {
        var read = FakeTool.named("read").throwing(() -> new IllegalStateException("disk on fire"));
        var rig = new Rig().provider(calling("read", "{\"path\":\"a.txt\"}")).tools(read);
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.EXECUTION_FAILED, kindOf(rig));
        assertEquals("IllegalStateException: disk on fire", resultOf(rig).text());
    }

    @Test
    @DisplayName("output-filter failure replaces sensitive output with HOOK_FAILED")
    void afterToolCallThrows() {
        var read = FakeTool.readOnly("read", "contents of a.txt");
        var rig = new Rig().provider(calling("read", "{\"path\":\"a.txt\"}")).tools(read)
                .hooks(new AgentHooks() {
                    @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation cancel) {
                        throw new IllegalStateException("audit sink unreachable");
                    }
                });
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.HOOK_FAILED, kindOf(rig));
        assertTrue(resultOf(rig).isError());
        assertTrue(!resultOf(rig).text().contains("contents of a.txt"));
        assertTrue(!resultOf(rig).details().toText().contains("contents of a.txt"));
        assertEquals(1, read.calls(), "filter failure must not retry the side effect");
    }

    @Test
    @DisplayName("I5/I6 hold when the tool throws, when the hook throws, and when both throw")
    void invariantsHoldWhenEverythingThrows() {
        var read = FakeTool.named("read").throwing(() -> new IllegalStateException("tool exploded"));
        var rig = new Rig().provider(calling("read", "{\"path\":\"a.txt\"}")).tools(read)
                .hooks(new AgentHooks() {
                    @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation cancel) {
                        return ToolDecision.ALLOW;
                    }
                    @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation cancel) {
                        throw new IllegalStateException("hook exploded too");
                    }
                });
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ErrorKind.HOOK_FAILED, kindOf(rig));
        assertEquals(List.of("ToolStart", "ToolEnd", "MessageStart", "MessageEnd"),
                rig.trace().subList(rig.trace().indexOf("ToolStart"), rig.trace().indexOf("ToolStart") + 4));
    }

    // ---- normalisation ---------------------------------------------------------------------------

    @Test
    @DisplayName("empty content is normalised to `(no output)` at the one site that builds the entry")
    void emptyContentIsNormalised() {
        var grep = FakeTool.named("grep").returning(new ToolResult.Ok(List.of(), Json.Null.NULL));
        var rig = new Rig().provider(calling("grep", "{\"pattern\":\"nothing\"}")).tools(grep);
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ToolMessages.NO_OUTPUT, resultOf(rig).text(),
                "an empty tool_result block is rejected outright by some providers");
        assertEquals(1, resultOf(rig).content().size());
    }

    @Test
    @DisplayName("a tool that returns null is normalised to `(no output)`, not to an empty text block")
    void nullResultIsNormalised() {
        var grep = FakeTool.named("grep").doing(_ -> null);
        var rig = new Rig().provider(calling("grep", "{\"pattern\":\"nothing\"}")).tools(grep);
        rig.run("hi");

        assertFunnelExitedOnce(rig);
        assertEquals(ToolMessages.NO_OUTPUT, resultOf(rig).text(),
                "ToolFunnel.execute maps null to ToolResult.text(\"\"), whose content is [Text(\"\")] — "
                        + "non-empty by the letter of emitOutcome, empty by the rule's intent");
    }

    // ---- kind-driven batching is decided from OUR registration -----------------------------------

    @Test
    @DisplayName("an unresolvable call is its own batch, so a mixed turn still emits in source order")
    void unknownCallDoesNotReorderResults() {
        var script = new ArrayList<ScriptedProvider.Step>();
        script.add(ScriptedProvider.emit(new LlmStreamEvent.Start()));
        script.addAll(ScriptedProvider.toolCall(0, "call-1", "read", "{\"path\":\"a.txt\"}"));
        script.addAll(ScriptedProvider.toolCall(1, "call-2", "gone", "{}"));
        script.addAll(ScriptedProvider.toolCall(2, "call-3", "read", "{\"path\":\"b.txt\"}"));
        script.add(ScriptedProvider.emit(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.tokens(10, 5), "r", sdk.agent.json.Json.nil())));

        var rig = new Rig().provider(ScriptedProvider.of(script))
                .tools(FakeTool.named("read").kind(ToolKind.READ_ONLY).answering("contents"));
        rig.run("hi");

        rig.sink.assertInvariants();
        assertEquals(List.of("call-1", "call-2", "call-3"),
                rig.sink.ofType(AgentEvent.ToolEnd.class).stream().map(AgentEvent.ToolEnd::toolCallId).toList(),
                "results stay in source order even when the first call is unknown");
        assertEquals("Tool gone not found", rig.sink.toolResult("call-2").orElseThrow().text());
    }
}
