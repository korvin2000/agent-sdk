package sdk.agent.tools.shell;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.support.Truncation;
import sdk.agent.tools.support.Utf8;

/// Executes one bash command and frames the result **stderr-before-stdout** — on a failed build
/// the diagnostic leads — which is why the two streams are captured separately and composed at the
/// boundary rather than merged with `redirectErrorStream(true)`. Every label is always emitted: a
/// uniform frame is parseable by a host and by the model with no case analysis, and the exit code
/// is never absent because every terminal path produces an [ExecOutcome].
public final class BashTool implements Tool<BashParams> {

    /// The timeout clause is rendered from the configured default, so the description cannot lie.
    private static final String DESCRIPTION = """
            Execute a bash command in the current working directory. Returns stdout and stderr. \
            Output is truncated to last %d lines or %dKB (whichever is hit first). If truncated, \
            full output is saved to a temp file. Commands time out after %d seconds by default; \
            pass `timeout` in seconds to change it.""";

    /// pi, verbatim.
    private static final String NO_OUTPUT = "(no output)";

    private final ToolEnvironment env;
    private final CommandRunner runner;
    private final ParamCodec<BashParams> params = ParamCodec.ofRecord(BashParams.class);
    private final String description;

    public BashTool(ToolEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
        this.runner = new CommandRunner(env);
        this.description = DESCRIPTION.formatted(Truncation.DEFAULT_MAX_LINES,
                                                 Truncation.DEFAULT_MAX_BYTES / 1024,
                                                 env.defaultCommandTimeout().toSeconds());
    }

    @Override public String name() { return "bash"; }

    @Override public String description() { return description; }

    @Override public ParamCodec<BashParams> params() { return params; }

    /// New — pi ships none, and this is the cheapest quality win in the tool set.
    @Override public List<String> promptGuidelines() {
        return List.of(
            "Use read instead of cat/head/tail/sed, and edit instead of sed -i.",
            "Quote paths that may contain spaces.",
            "stdin is closed. Do not run interactive commands; pass flags such as -y or -m instead.",
            "Pass timeout for anything long-running.",
            "Each call starts in the workspace root. Use cd /path && command for a one-off directory change.");
    }

    @Override public ToolResult execute(ToolInvocation<BashParams> call) throws Exception {
        String command = call.params().command();
        // A stale cwd is common — a `git worktree remove`, a cleaned build directory, a host that
        // resolved the root at startup. Without this, ProcessBuilder.start() fails with an
        // IOException whose message names the *shell*, not the directory.
        if (!Files.isDirectory(env.workspace())) {
            return ToolResult.error(ErrorKind.EXECUTION_FAILED,
                "Working directory does not exist: %s\nCannot execute bash commands."
                    .formatted(env.workspace()));
        }
        ShellSpec shell = env.shell();
        Duration timeout = call.params().timeout()
                .filter(seconds -> seconds > 0)
                .map(seconds -> Duration.ofSeconds(seconds.longValue()))
                .orElseGet(env::defaultCommandTimeout);

        var stdout = OutputCollector.stdout();
        var stderr = OutputCollector.stderr();
        ExecOutcome outcome = runner.run(shell, command, timeout, call.cancel(), call.progress(), stdout, stderr);
        return finish(outcome, shell, stdout, stderr);
    }

    /// The single terminal path. pi's abort/timeout path skips truncation entirely, dumping up to
    /// 100 KB into the context and bypassing the cap the success path enforces; here exit, timeout,
    /// cancel and signal all arrive at the same truncation, the same notices and the same details.
    ToolResult finish(ExecOutcome outcome, ShellSpec shell, OutputCollector stdout, OutputCollector stderr) {
        int code = exitCode(outcome);
        String body = frame(code,
                            OutputSanitizer.forModel(stderr.text()),
                            OutputSanitizer.forModel(stdout.text()));

        // The collectors' true totals, counted as the data arrived: the notice must name the size
        // of the command's output, not the size of the retained window or of the frame around it.
        Truncation.Result framed = Truncation.tail(body, env.limits());
        Truncation.Result truncation = framed.withSourceTotals(
                addLines(stdout.trueLines(), stderr.trueLines()),
                stdout.trueBytes() + stderr.trueBytes());

        var text = new StringBuilder(truncation.content());
        OutputCollector.Spill spill = OutputCollector.Spill.NONE;
        if (truncation.truncated()) {
            spill = OutputCollector.spill(spillChunks(code, stdout, stderr));
            text.append(notice(truncation, body, spill));
        }
        String suffix = suffix(outcome);
        if (!suffix.isEmpty()) text.append("\n\n").append(suffix);

        Json details = details(outcome, code, shell, stdout, stderr, truncation, spill);
        return outcome instanceof ExecOutcome.Exited(int c) && c == 0
                ? ToolResult.text(text.toString(), details)
                : ToolResult.error(errorKind(outcome), text.toString(), details);
    }

    // ---- framing ---------------------------------------------------------------------------

    private static String frame(int code, String stderr, String stdout) {
        String err = withoutTrailingNewline(stderr);
        String out = withoutTrailingNewline(stdout);
        var sb = new StringBuilder("EXIT_CODE: ").append(code).append("\nSTDERR:");
        if (!err.isEmpty()) sb.append('\n').append(err);
        sb.append("\nSTDOUT:");
        if (!out.isEmpty()) sb.append('\n').append(out);
        else if (err.isEmpty()) sb.append('\n').append(NO_OUTPUT);
        return sb.toString();
    }

    private static String withoutTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    /// pi's three tail notices, verbatim, with their conditions in pi's order of test. `{a}`/`{b}`
    /// come from the **true** totals the collectors counted, never from the retained window: pi
    /// reports `totalLines` from its own truncated rolling buffer, so a 1M-line command is announced
    /// as `[Showing lines 1801-2000 of 2000]`.
    static String notice(Truncation.Result t, String body, OutputCollector.Spill spill) {
        String where = spill.saved() ? ". Full output: " + spill.path() : "";
        int total = t.totalLines();
        int first = total - t.outputLines() + 1;
        String head;
        if (t.lastLinePartial()) {
            head = "[Showing last %s of line %d (line is %s)%s]".formatted(
                    Truncation.formatSize(t.outputBytes()), total, Truncation.formatSize(lastLineSize(body)), where);
        } else if (t.truncatedBy() == Truncation.Result.By.LINES) {
            head = "[Showing lines %d-%d of %d%s]".formatted(first, total, total, where);
        } else {
            head = "[Showing lines %d-%d of %d (%s limit)%s]".formatted(
                    first, total, total, Truncation.formatSize(t.limits().maxBytes()), where);
        }
        // Spilling is an optimisation, never a correctness requirement: on failure the notice is
        // emitted without the path and the reason follows, instead of pi's unhandled stream error.
        return "\n\n" + head + (spill.saved() ? "" : "\n[Full output could not be saved: " + spill.failure() + "]");
    }

    private static long lastLineSize(String body) {
        List<String> lines = Truncation.lines(body);
        return lines.isEmpty() ? 0 : Utf8.length(lines.getLast());
    }

    // ---- totals, outcome mapping and details -------------------------------------------------

    private static int addLines(int framed, long dropped) {
        return (int) Math.min(Integer.MAX_VALUE, (long) framed + dropped);
    }

    /// Never null, unlike nanocoder's. A timeout and an abort have no status of their own, so they
    /// borrow the conventions their signals would have produced (`timeout(1)`'s 124, `128 + SIGINT`).
    private static int exitCode(ExecOutcome outcome) {
        return switch (outcome) {
            case ExecOutcome.Exited(int code)  -> code;
            case ExecOutcome.TimedOut _        -> 124;
            case ExecOutcome.Cancelled _       -> 130;
            case ExecOutcome.Killed(int signal) -> 128 + signal;
        };
    }

    /// The last suffix is new: pi reports a signal-killed process (OOM, SIGSEGV, external `kill`)
    /// through the success path with no indication anything went wrong, because its guard is
    /// `exitCode !== 0 && exitCode !== null`.
    private static String suffix(ExecOutcome outcome) {
        return switch (outcome) {
            case ExecOutcome.Exited(int code)     -> code == 0 ? "" : "Command exited with code " + code;
            case ExecOutcome.Cancelled _          -> "Command aborted";
            case ExecOutcome.TimedOut(Duration d) -> "Command timed out after " + d.toSeconds() + " seconds";
            case ExecOutcome.Killed(int signal)   -> "Command was killed by signal " + signal;
        };
    }

    private static ErrorKind errorKind(ExecOutcome outcome) {
        return switch (outcome) {
            case ExecOutcome.Exited _, ExecOutcome.Killed _ -> ErrorKind.TOOL_REPORTED;
            case ExecOutcome.TimedOut _                     -> ErrorKind.TIMED_OUT;
            case ExecOutcome.Cancelled _                    -> ErrorKind.CANCELLED;
        };
    }

    private static String outcomeName(ExecOutcome outcome) {
        return switch (outcome) {
            case ExecOutcome.Exited _    -> "exited";
            case ExecOutcome.TimedOut _  -> "timed_out";
            case ExecOutcome.Cancelled _ -> "cancelled";
            case ExecOutcome.Killed _    -> "killed";
        };
    }

    /// The exit code, the chosen shell and the spill path survive on the failure path too, so a
    /// host can tell the user which bash ran and second-guess the `128 + n` signal inference.
    private static Json details(ExecOutcome outcome, int code, ShellSpec shell,
                                OutputCollector stdout, OutputCollector stderr,
                                Truncation.Result truncation, OutputCollector.Spill spill) {
        Json.Obj details = Json.obj(
                "exitCode", Json.num(code),
                "outcome", Json.str(outcomeName(outcome)),
                "shell", Json.str(shell.executable().toString()),
                "stdoutBytes", Json.num(stdout.trueBytes()),
                "stderrBytes", Json.num(stderr.trueBytes()),
                "truncation", truncation.toJson(),
                "fullOutputPath", spill.saved() ? Json.str(spill.path().toString()) : Json.nil());
        if (outcome instanceof ExecOutcome.Killed(int signal)) details = details.with("signal", Json.num(signal));
        if (!spill.saved() && spill.failure() != null) details = details.with("spillFailure", Json.str(spill.failure()));
        return details;
    }

    /// The forensic copy mirrors the frame so a host can line the two up, but carries the raw,
    /// unsanitised bytes — a host may want the escapes.
    private static List<byte[]> spillChunks(int code, OutputCollector stdout, OutputCollector stderr) {
        var chunks = new ArrayList<byte[]>(4);
        chunks.add(("EXIT_CODE: " + code + "\nSTDERR:\n").getBytes(StandardCharsets.UTF_8));
        chunks.add(stderr.rawBytes());
        chunks.add("\nSTDOUT:\n".getBytes(StandardCharsets.UTF_8));
        chunks.add(stdout.rawBytes());
        return chunks;
    }
}
