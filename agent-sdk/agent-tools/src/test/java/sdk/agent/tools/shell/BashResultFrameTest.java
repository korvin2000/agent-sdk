package sdk.agent.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ProgressSink;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.support.ToolException;
import sdk.agent.tools.support.Truncation;

/// The result frame, the three tail notices and the four suffixes — all exercised by calling
/// `finish` with pre-filled collectors, so nothing here spawns a process.
class BashResultFrameTest {

    private static final ShellSpec SHELL = ShellSpec.bash(Path.of("/usr/bin/bash"));

    @TempDir Path workspace;

    private BashTool tool() { return new BashTool(ToolEnvironment.local(workspace)); }

    private static OutputCollector filled(OutputCollector c, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        c.accept(bytes, bytes.length);
        return c;
    }

    private ToolResult finish(ExecOutcome outcome, String stderr, String stdout) {
        return tool().finish(outcome, SHELL,
                filled(OutputCollector.stdout(), stdout),
                filled(OutputCollector.stderr(), stderr));
    }

    // ---- the frame ---------------------------------------------------------------------------

    @Test @DisplayName("stderr leads, every label is emitted, and a trailing newline is not doubled")
    void framesBothStreams() {
        ToolResult r = finish(new ExecOutcome.Exited(0), "err\n", "out\n");
        assertEquals("""
                EXIT_CODE: 0
                STDERR:
                err
                STDOUT:
                out""", r.text());
        assertInstanceOf(ToolResult.Ok.class, r);
    }

    @Test void emptyStreamsRenderTheVerbatimNoOutput() {
        assertEquals("""
                EXIT_CODE: 0
                STDERR:
                STDOUT:
                (no output)""", finish(new ExecOutcome.Exited(0), "", "").text());
    }

    @Test void stderrOnlyKeepsTheStdoutLabel() {
        assertEquals("""
                EXIT_CODE: 1
                STDERR:
                boom
                STDOUT:

                Command exited with code 1""", finish(new ExecOutcome.Exited(1), "boom\n", "").text());
    }

    @Test void escapesAreSanitisedOnTheModelFacingPath() {
        ToolResult r = finish(new ExecOutcome.Exited(0), "", "[32mgreen[0m\n");
        assertTrue(r.text().endsWith("STDOUT:\ngreen"), r.text());
    }

    // ---- outcome mapping ---------------------------------------------------------------------

    @Test void zeroExitIsOk() {
        assertInstanceOf(ToolResult.Ok.class, finish(new ExecOutcome.Exited(0), "", "x"));
    }

    @Test @DisplayName("a non-zero exit is an Err with details intact")
    void nonZeroExitIsErrWithDetails() {
        ToolResult r = finish(new ExecOutcome.Exited(3), "boom\n", "");
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.TOOL_REPORTED, err.kind());
        assertTrue(r.text().contains("EXIT_CODE: 3"));
        assertTrue(r.text().contains("boom"));
        assertTrue(r.text().endsWith("\n\nCommand exited with code 3"));
        assertEquals(Json.num(3), details(err).get("exitCode").orElseThrow());
        assertEquals(Json.str(SHELL.executable().toString()), details(err).get("shell").orElseThrow());
        assertEquals(Json.str("exited"), details(err).get("outcome").orElseThrow());
    }

    @Test void timeoutKeepsPartialOutputAndNamesTheDeadline() {
        ToolResult r = finish(new ExecOutcome.TimedOut(Duration.ofSeconds(7)), "", "partial\n");
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.TIMED_OUT, err.kind());
        assertTrue(r.text().contains("partial"));
        assertTrue(r.text().endsWith("\n\nCommand timed out after 7 seconds"), r.text());
        assertEquals(Json.num(124), details(err).get("exitCode").orElseThrow());
    }

    @Test void cancellationIsAborted() {
        ToolResult r = finish(new ExecOutcome.Cancelled(), "", "half\n");
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.CANCELLED, err.kind());
        assertTrue(r.text().contains("half"));
        assertTrue(r.text().endsWith("\n\nCommand aborted"), r.text());
        assertEquals(Json.num(130), details(err).get("exitCode").orElseThrow());
    }

    @Test @DisplayName("a signal kill is reported, and the raw code survives in details")
    void signalKillIsReported() {
        ToolResult r = finish(new ExecOutcome.Killed(9), "", "");
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.TOOL_REPORTED, err.kind());
        assertTrue(r.text().endsWith("\n\nCommand was killed by signal 9"), r.text());
        assertEquals(Json.num(137), details(err).get("exitCode").orElseThrow());
        assertEquals(Json.num(9), details(err).get("signal").orElseThrow());
        assertEquals(Json.str("killed"), details(err).get("outcome").orElseThrow());
    }

    // ---- the three tail notices ----------------------------------------------------------------

    @Test @DisplayName("the line-cap notice reports the true totals, not the retained window")
    void lineTruncationNotice() throws IOException {
        var body = new StringBuilder();
        for (int i = 1; i <= 5000; i++) body.append(i).append('\n');
        ToolResult r = finish(new ExecOutcome.Exited(0), "", body.toString());
        String text = r.text();
        assertTrue(text.contains("\n5000\n\n[Showing lines 3001-5000 of 5000. Full output: "), tail(text));
        assertTrue(text.endsWith("]"), tail(text));
        Path spilled = spillPathIn(text);
        assertTrue(Files.size(spilled) > 20_000, "spill file is the full output");
    }

    @Test void byteCapNoticeNamesTheLimit() {
        var body = new StringBuilder();
        for (int i = 0; i < 100; i++) body.append("x".repeat(999)).append('\n');     // 100 KB, 100 lines
        String text = finish(new ExecOutcome.Exited(0), "", body.toString()).text();
        assertTrue(text.contains(" of 100 (50.0KB limit). Full output: "), tail(text));
        assertTrue(text.contains("\n\n[Showing lines "), tail(text));
    }

    @Test @DisplayName("one oversized last line gets pi's third notice")
    void partialLastLineNotice() {
        String text = finish(new ExecOutcome.Exited(0), "", "q".repeat(60_000)).text();
        assertTrue(text.contains("\n\n[Showing last 50.0KB of line 1 (line is 58.6KB). Full output: "), tail(text));
    }

    @Test @DisplayName("a spill that failed drops the path from the notice and says why")
    void noticeWithoutASpillFile() {
        String body = "EXIT_CODE: 0\nSTDERR:\nSTDOUT:\n" + "a\n".repeat(5000);
        Truncation.Result t = Truncation.tail(body, Truncation.Limits.DEFAULT);
        String notice = BashTool.notice(t, body, new OutputCollector.Spill(null, "No space left on device"));
        assertFalse(notice.contains("Full output: "));
        assertTrue(notice.startsWith("\n\n[Showing lines "), notice);
        assertTrue(notice.endsWith("]\n[Full output could not be saved: No space left on device]"), notice);
    }

    // ---- what happens before a spawn -------------------------------------------------------------

    @Test void aMissingWorkingDirectoryIsRefusedBeforeSpawning() throws Exception {
        Path gone = workspace.resolve("removed-worktree");
        var env = ToolEnvironment.builder(gone).build();
        ToolResult r = new BashTool(env).execute(call(new BashParams("echo hi", Optional.empty())));
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.EXECUTION_FAILED, err.kind());
        assertEquals("Working directory does not exist: %s\nCannot execute bash commands."
                .formatted(gone.toAbsolutePath().normalize()), r.text());
    }

    // ---- shipped text ----------------------------------------------------------------------------

    @Test void descriptionRendersTheConfiguredTimeout() {
        var env = ToolEnvironment.builder(workspace).defaultCommandTimeout(Duration.ofSeconds(45)).build();
        assertEquals("Execute a bash command in the current working directory. Returns stdout and stderr. "
                + "Output is truncated to last 2000 lines or 50KB (whichever is hit first). If truncated, "
                + "full output is saved to a temp file. Commands time out after 45 seconds by default; "
                + "pass `timeout` in seconds to change it.", new BashTool(env).description());
    }

    @Test void shippedNameAndGuidelines() {
        BashTool bash = tool();
        assertEquals("bash", bash.name());
        assertEquals(5, bash.promptGuidelines().size());
        assertTrue(bash.promptGuidelines().contains(
                "stdin is closed. Do not run interactive commands; pass flags such as -y or -m instead."));
    }

    @Test void schemaMakesTimeoutOptional() {
        Json.Obj schema = tool().params().schema();
        assertEquals(Json.arr(Json.str("command")), schema.get("required").orElseThrow());
        assertTrue(schema.toText().contains("\"Bash command to execute\""));
        assertTrue(schema.toText().contains("\"Timeout in seconds (optional)\""));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Json.Obj details(ToolResult.Err err) { return (Json.Obj) err.details(); }

    private static ToolInvocation<BashParams> call(BashParams params) {
        return new ToolInvocation<>("id", "bash", params, Json.Obj.EMPTY, Cancellation.create(), ProgressSink.NONE);
    }

    private static Path spillPathIn(String text) {
        int start = text.lastIndexOf("Full output: ") + "Full output: ".length();
        return Path.of(text.substring(start, text.length() - 1));
    }

    private static String tail(String text) {
        return text.length() <= 300 ? text : "..." + text.substring(text.length() - 300);
    }
}
