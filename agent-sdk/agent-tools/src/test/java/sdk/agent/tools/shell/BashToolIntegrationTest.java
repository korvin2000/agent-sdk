package sdk.agent.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.concurrent.Fork;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ProgressSink;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.support.ToolException;

/// Everything here actually spawns. Skipped wholesale when the machine has no bash, so the pure
/// tests still run on a bare CI image.
class BashToolIntegrationTest {

    private static ShellSpec shell;

    @TempDir Path workspace;

    @BeforeAll static void findAShell() {
        try {
            shell = ShellDiscovery.platform().discover();
        } catch (ToolException _) {
            shell = null;
        }
        Assumptions.assumeTrue(shell != null, "no bash on this machine");
    }

    private ToolResult run(String command, Integer timeoutSeconds, Cancellation cancel) throws Exception {
        var env = ToolEnvironment.builder(workspace).defaultCommandTimeout(Duration.ofSeconds(60)).build();
        var call = new ToolInvocation<>("id", "bash",
                new BashParams(command, Optional.ofNullable(timeoutSeconds)),
                Json.Obj.EMPTY, cancel, ProgressSink.NONE);
        return new BashTool(env).execute(call);
    }

    private ToolResult run(String command) throws Exception { return run(command, null, Cancellation.create()); }

    @Test @DisplayName("stdout and stderr are captured separately and framed stderr-first")
    void framesBothStreams() throws Exception {
        ToolResult r = run("echo out; echo err >&2");
        assertInstanceOf(ToolResult.Ok.class, r);
        assertEquals("""
                EXIT_CODE: 0
                STDERR:
                err
                STDOUT:
                out""", r.text());
    }

    @Test void aCommandWithNoOutputRendersTheVerbatimNotice() throws Exception {
        assertEquals("""
                EXIT_CODE: 0
                STDERR:
                STDOUT:
                (no output)""", run("true").text());
    }

    @Test @DisplayName("a non-zero exit is an Err with details intact")
    void nonZeroExitIsAnErr() throws Exception {
        ToolResult r = run("echo before; echo boom >&2; exit 3");
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.TOOL_REPORTED, err.kind());
        assertTrue(r.text().contains("EXIT_CODE: 3"), r.text());
        assertTrue(r.text().contains("boom"), r.text());
        assertTrue(r.text().contains("before"), r.text());
        assertTrue(r.text().endsWith("\n\nCommand exited with code 3"), r.text());
        var details = (Json.Obj) err.details();
        assertEquals(Json.num(3), details.get("exitCode").orElseThrow());
        assertEquals(Json.str(shell.executable().toString()), details.get("shell").orElseThrow());
    }

    @Test @DisplayName("a timeout keeps the partial output it already printed")
    void timeoutKeepsPartialOutput() throws Exception {
        ToolResult r = run("echo partial; sleep 30", 1, Cancellation.create());
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.TIMED_OUT, err.kind());
        assertTrue(r.text().contains("partial"), r.text());
        assertTrue(r.text().endsWith("\n\nCommand timed out after 1 seconds"), r.text());
    }

    @Test @DisplayName("cancellation kills the tree and is classified before the exit code is read")
    void cancellationReportsAborted() throws Exception {
        var cancel = Cancellation.create();
        ToolResult r;
        try (var fork = Fork.open()) {
            fork.run(() -> {
                try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                cancel.cancel();
            });
            r = run("sleep 30", 60, cancel);
        }
        var err = assertInstanceOf(ToolResult.Err.class, r);
        assertEquals(ErrorKind.CANCELLED, err.kind());
        assertTrue(r.text().endsWith("\n\nCommand aborted"), r.text());
    }

    @Test @DisplayName("stdin is closed: a command that reads it terminates immediately")
    void stdinIsClosed() throws Exception {
        ToolResult r = run("cat", 10, Cancellation.create());
        assertInstanceOf(ToolResult.Ok.class, r);
        assertTrue(r.text().contains("(no output)"), r.text());
    }

    @Test @DisplayName("the tail notice reports the true totals a 5000-line run produced")
    void tailTruncationNoticeHasTrueTotals() throws Exception {
        ToolResult r = run("seq 1 5000");
        String text = r.text();
        assertTrue(text.contains("\n5000\n\n[Showing lines 3001-5000 of 5000. Full output: "), tail(text));
        assertTrue(text.startsWith("3001\n"), text.substring(0, Math.min(40, text.length())));
        Path spilled = spillPathIn(text);
        assertTrue(Files.exists(spilled), spilled.toString());
        assertTrue(Files.size(spilled) > 20_000, "the spill file holds the whole run");
    }

    @Test void escapeSequencesNeverReachTheModel() throws Exception {
        ToolResult r = run("printf 'a\\033[31mred\\033[0m\\n'");
        assertTrue(r.text().endsWith("STDOUT:\nared"), r.text());
    }

    @Test @DisplayName("each call starts in the workspace root")
    void runsInTheWorkspace() throws Exception {
        Files.writeString(workspace.resolve("marker.txt"), "x");
        assertTrue(run("ls").text().contains("marker.txt"), "workspace listing");
    }

    private static Path spillPathIn(String text) {
        int start = text.lastIndexOf("Full output: ") + "Full output: ".length();
        return Path.of(text.substring(start, text.length() - 1));
    }

    private static String tail(String text) {
        return text.length() <= 300 ? text : "..." + text.substring(text.length() - 300);
    }
}
