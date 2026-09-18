package sdk.agent.tools.shell;

import java.time.Duration;

/// How a command ended, as data. This replaces pi's exception-as-protocol (`bash.ts:106-124`),
/// where the caller tests `err.message === "aborted"` and `err.message.startsWith("timeout:")` —
/// so any command whose own error message begins with `timeout:` is misclassified.
///
/// Every terminal path produces one of these, which is why the result frame's `EXIT_CODE` line is
/// never absent the way nanocoder's is.
sealed interface ExecOutcome {

    /// The process exited on its own; `code` is its exit status.
    record Exited(int code) implements ExecOutcome { }

    /// The deadline elapsed and the tree was killed; partial output is still returned.
    record TimedOut(Duration after) implements ExecOutcome { }

    /// [sdk.agent.concurrent.Cancellation] fired. Classified **before** the exit code is read, so
    /// the kill the listener performed is not reported as `Command exited with code 143`.
    record Cancelled() implements ExecOutcome { }

    /// Inferred from `128 + n` on POSIX. It is an inference, not a guarantee: a script that does
    /// `exit 137` is indistinguishable from one the OOM killer took. The raw code survives in
    /// `details` so a host can second-guess it.
    record Killed(int signal) implements ExecOutcome { }
}
