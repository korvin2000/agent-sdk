package sdk.agent.concurrent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/// The one [ScopedValue] in the SDK, carrying the one record that identifies the running turn.
/// It replaces the `signal?` parameter pi threads through nine signatures.
///
/// **Hard rule (C10):** `ScopedValue` bindings do not propagate into a raw
/// `Thread.ofVirtual().start(...)`. Every background task must be started through [Fork], which
/// rebinds this value. A forgotten rebind does not fail loudly — it surfaces as a tool acting on
/// the wrong run — which is why [#current] throws instead of defaulting.
public record RunScope(String runId, int turnIndex, Cancellation cancel) {

    public static final ScopedValue<RunScope> CURRENT = ScopedValue.newInstance();

    public RunScope {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(cancel, "cancel");
    }

    /// @throws IllegalStateException when unbound — never a silent default
    public static RunScope current() { return CURRENT.orElseThrow(RunScope::unbound); }

    /// `ScopedValue.orElse` rejects `null`, so this is the one place that tests `isBound()`.
    public static Optional<RunScope> currentIfBound() { return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty(); }

    /// Runs `task` with this scope bound on the current thread.
    public void run(Runnable task) { ScopedValue.where(CURRENT, this).run(task); }

    /// `Carrier.call` takes a `ScopedValue.CallableOp`, not a `Callable` — hence the method
    /// reference. Do not "clean it up".
    public <T> T call(Callable<T> task) throws Exception { return ScopedValue.where(CURRENT, this).call(task::call); }

    public RunScope withTurn(int turn) { return new RunScope(runId, turn, cancel); }

    private static IllegalStateException unbound() {
        return new IllegalStateException(
                "RunScope is unbound on " + Thread.currentThread()
                + ". Agent work must run inside ScopedValue.where(RunScope.CURRENT, scope), and a "
                + "background task must be started with Fork.fork/Fork.run, which rebinds it. "
                + "A raw Thread.ofVirtual().start(...) does NOT inherit ScopedValue bindings (C10).");
    }
}
