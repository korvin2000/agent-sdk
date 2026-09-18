package sdk.agent.tools.shell;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.concurrent.Fork;
import sdk.agent.tool.ProgressSink;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;

/// Spawns one command in the workspace and drains it: `ProcessBuilder`, two virtual-thread pumps,
/// and `ProcessHandle.descendants()` for the kill. How the command ended is data ([ExecOutcome]),
/// never an exception message to parse.
final class CommandRunner {

    static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final int CHUNK = 8 * 1024;

    /// A hint, not a control: `pytest -v`, `docker` and a long tail of runners colour
    /// unconditionally, which is why [OutputSanitizer] runs on the model-facing text regardless.
    private static final Map<String, String> ENVIRONMENT = Map.of("TERM", "dumb", "NO_COLOR", "1", "CI", "1");

    private final ToolEnvironment env;

    CommandRunner(ToolEnvironment env) { this.env = Objects.requireNonNull(env, "env"); }

    ExecOutcome run(ShellSpec shell, String command, Duration timeout, Cancellation cancel,
                    ProgressSink progress, OutputCollector stdout, OutputCollector stderr)
            throws IOException, InterruptedException {

        var pb = new ProcessBuilder(argv(shell, command)).directory(env.workspace().toFile());
        pb.environment().putAll(ENVIRONMENT);
        // stdin CLOSED, and load-bearing: an inherited stdin makes `git commit` without -m block
        // forever. Redirect.DISCARD is output-only, so it has to be the null device by name.
        pb.redirectInput(Redirect.from(new File(IS_WINDOWS ? "NUL" : "/dev/null")));
        Process proc = pb.start();
        ProcessHandle handle = proc.toHandle();

        // The listener only asks the tree to terminate, so the cancelling thread never blocks; the
        // graceful-then-forcible kill runs here, on the thread the cancel interrupts.
        try (var fork = Fork.open();
             var _ = cancel.onCancel(() -> terminate(handle))) {

            fork.fork(() -> pump(proc.getInputStream(), stdout, progress));
            fork.fork(() -> pump(proc.getErrorStream(), stderr, progress));

            boolean done;
            try {
                done = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                killTree(handle);
                if (!cancel.isCancelled()) throw e;                 // a foreign interrupt is not ours to swallow
                readerGrace(fork);
                return new ExecOutcome.Cancelled();
            }
            if (!done) {
                killTree(handle);
                readerGrace(fork);
                return new ExecOutcome.TimedOut(timeout);
            }
            readerGrace(fork);

            // BEFORE classifying the exit code: the listener has already terminated the tree, so
            // waitFor returns an ordinary code and the abort would read as `exited with code 143`.
            if (cancel.isCancelled()) return new ExecOutcome.Cancelled();

            int code = proc.exitValue();
            return code > 128 && !IS_WINDOWS ? new ExecOutcome.Killed(code - 128)
                                             : new ExecOutcome.Exited(code);
        }
    }

    /// 100 ms post-exit grace. `waitFor()` returns as soon as the process exits, but a reader
    /// blocked on a pipe a daemonised GRANDCHILD inherited blocks indefinitely — do not "simplify"
    /// this into a plain join.
    private static void readerGrace(Fork fork) throws InterruptedException {
        try { fork.joinUntil(Instant.now().plusMillis(100)); }
        catch (TimeoutException _) { fork.cancelAll(); }
    }

    private static List<String> argv(ShellSpec shell, String command) {
        var argv = new ArrayList<String>(shell.args().size() + 2);
        argv.add(shell.executable().toString());
        argv.addAll(shell.args());
        argv.add(command);
        return argv;
    }

    private static Void pump(InputStream in, OutputCollector sink, ProgressSink progress) throws IOException {
        byte[] buf = new byte[CHUNK];
        var carry = new ByteArrayOutputStream();
        for (int n; (n = in.read(buf)) > 0; ) {
            sink.accept(buf, n);
            if (progress != ProgressSink.NONE) emit(progress, carry, buf, n);
        }
        return null;
    }

    /// Streaming progress decodes only whole characters and carries the straddling tail, so a
    /// multi-byte character split across two reads never reaches a host as U+FFFD.
    private static void emit(ProgressSink progress, ByteArrayOutputStream carry, byte[] buf, int n) {
        carry.write(buf, 0, n);
        byte[] bytes = carry.toByteArray();
        int complete = OutputCollector.completeUtf8Length(bytes, bytes.length);
        if (complete == 0) return;
        carry.reset();
        carry.write(bytes, complete, bytes.length - complete);
        progress.update(ToolResult.text(new String(bytes, 0, complete, StandardCharsets.UTF_8)));
    }

    /// SIGTERM to the whole tree, without waiting.
    private static void terminate(ProcessHandle h) {
        h.descendants().forEach(ProcessHandle::destroy);
        h.destroy();
    }

    /// `descendants()` is a snapshot, so a process forking as you kill escapes: kill the root,
    /// re-walk once, best effort. The SIGTERM grace must actually elapse — `get(2, SECONDS)` waits,
    /// `onExit().orTimeout(...)`.isDone()` would force-kill at once and lose the partial output.
    static void killTree(ProcessHandle h) {
        terminate(h);                                           // SIGTERM first, so output flushes
        try { h.onExit().get(2, TimeUnit.SECONDS); }
        catch (TimeoutException _) {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
        }
        catch (ExecutionException _) { /* already gone */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); h.destroyForcibly(); }
    }
}
