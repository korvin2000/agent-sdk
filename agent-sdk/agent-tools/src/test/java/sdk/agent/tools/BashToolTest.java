package sdk.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ProgressSink;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BashToolTest {
    @Test
    void closesStdinAndRetainsOnlyBoundedOutputTail(@TempDir Path workspace) throws Exception {
        ToolResult result = execute(workspace, Duration.ofSeconds(5),
                "head -c 70000 /dev/zero | tr '\\0' x; read -r line && printf BAD || printf EOF");

        var ok = assertInstanceOf(ToolResult.Ok.class, result);
        assertTrue(ok.content().getFirst() instanceof sdk.agent.message.ContentBlock.Text);
        assertTrue(result.text().contains("EOF"));
        assertTrue(result.text().contains("output truncated"));
        assertTrue(result.text().contains("[exit code: 0]"));
        assertEquals(70003L, number(ok.details(), "totalBytes"));
        assertEquals(true, bool(ok.details(), "truncated"));
        assertTrue(result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 66_000);
    }

    @Test
    void mapsNonzeroExitToToolReportedAndKeepsOutput(@TempDir Path workspace) throws Exception {
        ToolResult result = execute(workspace, Duration.ofSeconds(5), "printf failure; exit 7");

        var error = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals(ErrorKind.TOOL_REPORTED, error.kind());
        assertTrue(result.text().contains("failure"));
        assertTrue(result.text().contains("[exit code: 7]"));
        assertEquals(7L, number(error.details(), "exitCode"));
    }

    @Test
    void mapsShellLaunchFailureToUnavailable(@TempDir Path workspace) throws Exception {
        var environment = new ToolEnvironment(workspace,
                java.util.List.of("definitely-not-a-real-shell-for-agent-tests"), Duration.ofSeconds(1));
        var call = new ToolInvocation<>("call", "bash", new BashTool.Args("echo never"), Json.Obj.EMPTY,
                Cancellation.create(), ProgressSink.NONE);

        ToolResult result = new BashTool(environment).execute(call);
        assertEquals(ErrorKind.UNAVAILABLE, assertInstanceOf(ToolResult.Err.class, result).kind());
    }

    @Test
    void timeoutTerminatesShellAndReturnsPromptly(@TempDir Path workspace) throws Exception {
        long started = System.nanoTime();
        ToolResult result = execute(workspace, Duration.ofMillis(500),
                "echo $$ > parent.pid; trap 'wait; exit 0' TERM; sleep 30 & echo $! > child.pid; wait");

        var error = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals(ErrorKind.TIMED_OUT, error.kind());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 4);
        assertDead(workspace.resolve("parent.pid"));
        assertDead(workspace.resolve("child.pid"));
    }

    @Test
    void cancellationStopsKnownDescendantWithoutBlockingCanceller(@TempDir Path workspace) throws Exception {
        Path childPid = workspace.resolve("child.pid");
        Cancellation cancel = Cancellation.create();
        var tool = new BashTool(new ToolEnvironment(workspace, java.util.List.of("bash", "-c"), Duration.ofSeconds(30)));
        var invocation = new ToolInvocation<>("call", "bash", new BashTool.Args(
                "echo $$ > parent.pid; trap 'wait; exit 0' TERM; sleep 30 & echo $! > child.pid; wait"),
                Json.Obj.EMPTY, cancel, ProgressSink.NONE);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> tool.execute(invocation));
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!Files.exists(childPid) || Files.size(childPid) == 0) && System.nanoTime() < readyDeadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(childPid) && Files.size(childPid) > 0,
                    "shell should have started the known descendant");
            long cancelStarted = System.nanoTime();
            cancel.cancel();
            assertTrue(Duration.ofNanos(System.nanoTime() - cancelStarted).toMillis() < 250,
                    "cancellation callback must not wait for process cleanup");
            ToolResult result = future.get(4, TimeUnit.SECONDS);
            assertEquals(ErrorKind.CANCELLED, assertInstanceOf(ToolResult.Err.class, result).kind());
            assertDead(workspace.resolve("parent.pid"));
            assertDead(childPid);
        }
    }

    private static void assertDead(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (handle.isPresent() && handle.get().isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(handle.isEmpty() || !handle.get().isAlive(), "process must terminate: " + pid);
    }

    private static ToolResult execute(Path workspace, Duration timeout, String command) throws Exception {
        var environment = new ToolEnvironment(workspace, java.util.List.of("bash", "-c"), timeout);
        var call = new ToolInvocation<>("call", "bash", new BashTool.Args(command), Json.Obj.EMPTY,
                Cancellation.create(), ProgressSink.NONE);
        return new BashTool(environment).execute(call);
    }

    private static long number(Json details, String name) {
        var value = assertInstanceOf(Json.Num.class, assertInstanceOf(Json.Obj.class, details).get(name).orElseThrow());
        return value.value().longValueExact();
    }

    private static boolean bool(Json details, String name) {
        return assertInstanceOf(Json.Bool.class, assertInstanceOf(Json.Obj.class, details).get(name).orElseThrow()).value();
    }
}
