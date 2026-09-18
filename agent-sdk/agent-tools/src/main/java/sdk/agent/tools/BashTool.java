package sdk.agent.tools;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.concurrent.Fork;
import sdk.agent.json.Doc;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;

/// Runs one unsandboxed shell command in the configured workspace. Permission hooks or an OS sandbox,
/// not this tool, are responsible for deciding whether a command may run.
public final class BashTool implements Tool<BashTool.Args> {
    private static final int TAIL_BYTES = 64 * 1024;
    private static final Duration POLL = Duration.ofMillis(50);
    private static final Duration CLEANUP_GRACE = Duration.ofSeconds(1);
    private static final ParamCodec<Args> PARAMS = ParamCodec.ofRecord(Args.class);

    private final ToolEnvironment environment;

    public BashTool(ToolEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public record Args(@Doc("Command to run through the configured shell in the workspace") String command) { }

    @Override public String name() { return "bash"; }

    @Override public String description() { return "Run an unsandboxed shell command in the workspace."; }

    @Override public ParamCodec<Args> params() { return PARAMS; }

    @Override public ToolKind kind() { return ToolKind.MUTATING; }

    @Override public List<String> promptGuidelines() {
        return List.of("Use bash only when shell execution is necessary; it is unsandboxed.");
    }

    @Override public ToolResult execute(ToolInvocation<Args> call) throws Exception {
        Objects.requireNonNull(call, "call");
        Args args = call.params();
        if (args == null || args.command() == null || args.command().isBlank()) {
            return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "bash command must not be blank");
        }
        if (call.cancel().isCancelled()) {
            return ToolResult.error(ErrorKind.CANCELLED, "shell command was cancelled before it started");
        }

        Process process;
        try {
            var command = new java.util.ArrayList<>(environment.shell());
            command.add(args.command());
            process = new ProcessBuilder(command)
                    .directory(environment.workspace().toFile())
                    .redirectErrorStream(true)
                    .start();
            try {
                process.getOutputStream().close();             // every command observes EOF on stdin
            } catch (IOException e) {
                process.destroyForcibly();
                return ToolResult.error(ErrorKind.UNAVAILABLE, "could not close shell stdin: " + e.getMessage());
            }
        } catch (IOException e) {
            return ToolResult.error(ErrorKind.UNAVAILABLE, "could not start configured shell: " + e.getMessage());
        }

        var output = new Tail();
        var targets = ConcurrentHashMap.<ProcessHandle>newKeySet();
        var processHandle = process.toHandle();
        targets.add(processHandle);
        var merged = process.getInputStream();
        Outcome outcome = null;
        boolean cleanupDone = false;
        Cancellation.Registration registration = call.cancel().onCancel(() -> requestStop(process, targets));
        try (var fork = Fork.open(CLEANUP_GRACE)) {
            Fork.Handle<Void> drain = fork.fork("bash-output", () -> {
                try (merged) {
                    drain(merged, output);
                }
                return null;
            });

            outcome = waitFor(process, call.cancel(), environment.commandTimeout());
            if (outcome != Outcome.EXITED) {
                requestStop(process, targets);
                stopAndForce(process, targets);
                cleanupDone = true;
                try {
                    merged.close();
                } catch (IOException ignored) {
                    // The process is already being stopped; output cleanup is best effort.
                }
            }
            try {
                waitForDrain(fork, drain, merged);
            } catch (IOException | java.util.concurrent.CancellationException e) {
                if (outcome == Outcome.EXITED) throw e;
                // A closed pipe can make the drainer report an I/O error after cancellation.
            }

            Integer exitCode = exitedCode(process);
            Snapshot snapshot = output.snapshot();
            if (outcome == Outcome.CANCELLED) {
                return result(ErrorKind.CANCELLED, "shell command was cancelled", snapshot, exitCode);
            }
            if (outcome == Outcome.TIMED_OUT) {
                return result(ErrorKind.TIMED_OUT, "shell command timed out", snapshot, exitCode);
            }
            if (exitCode == null) {
                throw new IOException("shell process did not terminate after reporting completion");
            }
            if (exitCode != 0) {
                return result(ErrorKind.TOOL_REPORTED, "shell command exited with status " + exitCode, snapshot, exitCode);
            }
            return ToolResult.text(render(snapshot, exitCode, null), details(snapshot, exitCode));
        } catch (InterruptedException e) {
            requestStop(process, targets);
            stopAndForce(process, targets);
            cleanupDone = true;
            try {
                merged.close();
            } catch (IOException ignored) {
                // The process is already being stopped; output cleanup is best effort.
            }
            Thread.currentThread().interrupt();
            return result(ErrorKind.CANCELLED, "shell command was interrupted", output.snapshot(), exitedCode(process));
        } finally {
            registration.close();
            if (!cleanupDone && (outcome != Outcome.EXITED || targets.stream().anyMatch(ProcessHandle::isAlive))) {
                requestStop(process, targets);
                stopAndForce(process, targets);
                try {
                    merged.close();
                } catch (IOException ignored) {
                    // The process is already being stopped; output cleanup is best effort.
                }
            }
        }

    }

    private static Outcome waitFor(Process process, Cancellation cancel, Duration timeout) throws InterruptedException {
        long started = System.nanoTime();
        long timeoutNanos = boundedNanos(timeout);
        while (true) {
            if (cancel.isCancelled()) return Outcome.CANCELLED;
            long elapsed = System.nanoTime() - started;
            if (elapsed >= timeoutNanos) return Outcome.TIMED_OUT;
            long waitNanos = Math.min(timeoutNanos - elapsed, POLL.toNanos());
            if (process.waitFor(waitNanos, TimeUnit.NANOSECONDS)) {
                return cancel.isCancelled() ? Outcome.CANCELLED : Outcome.EXITED;
            }
        }
    }

    /// This callback deliberately performs no wait: [Cancellation.cancel] invokes it on its caller's thread.
    private static void requestStop(Process process, Set<ProcessHandle> targets) {
        var parent = process.toHandle();
        var captured = parent.descendants().toList();
        targets.add(parent);
        targets.addAll(captured);
        captured.forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        if (parent.isAlive()) parent.destroy();
    }

    /// Waits a bounded grace period, then force-kills descendants before their parent.
    private static void stopAndForce(Process process, Set<ProcessHandle> targets) {
        long deadline = System.nanoTime() + CLEANUP_GRACE.toNanos();
        while (targets.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(Math.min(10, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        var parent = process.toHandle();
        targets.stream().filter(handle -> !handle.equals(parent)).forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (parent.isAlive()) parent.destroyForcibly();
    }

    private static void waitForDrain(Fork fork, Fork.Handle<Void> drain, InputStream merged) throws Exception {
        try {
            fork.joinUntil(Instant.now().plus(CLEANUP_GRACE));
        } catch (TimeoutException e) {
            merged.close();
            fork.cancelAll();
            try {
                fork.joinUntil(Instant.now().plus(CLEANUP_GRACE));
            } catch (TimeoutException ignored) {
                throw new IOException("shell output drain did not stop");
            }
        }
        try {
            drain.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw new IOException("shell output drain failed", e.getCause());
        }
    }

    private static void drain(InputStream input, Tail output) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read != 0) output.append(buffer, read);
        }
    }

    private static long boundedNanos(Duration duration) {
        try {
            return Math.min(duration.toNanos(), Long.MAX_VALUE / 2);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE / 2;
        }
    }

    private static Integer exitedCode(Process process) {
        return process.isAlive() ? null : process.exitValue();
    }

    private static ToolResult result(ErrorKind kind, String status, Snapshot output, Integer exitCode) {
        return ToolResult.error(kind, render(output, exitCode, status), details(output, exitCode));
    }

    private static String render(Snapshot output, Integer exitCode, String status) {
        StringBuilder text = new StringBuilder(new String(output.bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n');
        if (status != null) text.append('[').append(status).append("]\n");
        if (exitCode != null) text.append("[exit code: ").append(exitCode).append("]\n");
        if (output.truncated) text.append("[output truncated: showing final ").append(output.bytes.length)
                .append(" of ").append(output.totalBytes).append(" bytes]\n");
        return text.toString();
    }

    private static Json details(Snapshot output, Integer exitCode) {
        var details = new LinkedHashMap<String, Json>();
        if (exitCode != null) details.put("exitCode", Json.num(exitCode));
        details.put("totalBytes", Json.num(output.totalBytes));
        details.put("truncated", Json.bool(output.truncated));
        return Json.obj(details);
    }

    private enum Outcome { EXITED, CANCELLED, TIMED_OUT }

    private record Snapshot(byte[] bytes, long totalBytes, boolean truncated) { }

    /// A byte ring avoids retaining arbitrary command output while preserving the exact final tail.
    private static final class Tail {
        private final byte[] bytes = new byte[TAIL_BYTES];
        private int start;
        private int size;
        private long totalBytes;

        synchronized void append(byte[] source, int length) {
            totalBytes = Math.addExact(totalBytes, length);
            if (length >= bytes.length) {
                System.arraycopy(source, length - bytes.length, bytes, 0, bytes.length);
                start = 0;
                size = bytes.length;
                return;
            }
            int discarded = Math.max(0, size + length - bytes.length);
            start = (start + discarded) % bytes.length;
            size -= discarded;
            int end = (start + size) % bytes.length;
            int first = Math.min(length, bytes.length - end);
            System.arraycopy(source, 0, bytes, end, first);
            System.arraycopy(source, first, bytes, 0, length - first);
            size += length;
        }

        synchronized Snapshot snapshot() {
            byte[] tail = new byte[size];
            int first = Math.min(size, bytes.length - start);
            System.arraycopy(bytes, start, tail, 0, first);
            System.arraycopy(bytes, 0, tail, first, size - first);
            return new Snapshot(tail, totalBytes, totalBytes > size);
        }
    }
}
