package sdk.agent.concurrent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/// A one-shot cancellation token. Cancellation reaches work through three channels at once: the
/// flag ([#isCancelled]), thread interrupt ([#interruptOnCancel]) and callbacks ([#onCancel]) — the
/// flag alone is insufficient for a thread blocked in `Process.waitFor()` or a socket read.
///
/// Listeners run exactly once, on the cancelling thread, in registration order; a listener that
/// throws does not stop the others. A listener registered after cancellation fires immediately.
public final class Cancellation {

    private static final System.Logger LOG = System.getLogger(Cancellation.class.getName());

    /// Scoped to one [Cancellation]. `close()` declares no checked exception so every
    /// registration site is a plain try-with-resources; deregistration cannot fail.
    public interface Registration extends AutoCloseable {
        @Override void close();

        Registration NONE = () -> { };
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Entry> listeners = new CopyOnWriteArrayList<>();

    private Cancellation() { }

    public static Cancellation create() { return new Cancellation(); }

    /// ONE-WAY, parent → child. Cancelling the parent cancels the child; cancelling the child never
    /// touches the parent — a sub-agent that gives up must not be able to kill the run that spawned it.
    public static Cancellation linkedTo(Cancellation parent) {
        var child = new Cancellation();
        Registration link = parent.onCancel(child::cancel);
        child.onCancel(link::close);                     // a child cancelled on its own stops listening
        return child;
    }

    public boolean isCancelled() { return cancelled.get(); }

    /// @throws CancelledException if cancelled
    public void throwIfCancelled() {
        if (cancelled.get()) throw new CancelledException();
    }

    /// Fires immediately (on the calling thread) if already cancelled.
    public Registration onCancel(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (cancelled.get()) { runSafely(listener); return Registration.NONE; }
        var entry = new Entry(listener);
        listeners.add(entry);
        // Lost race: cancel() ran between the check above and the add. Whoever removes the entry runs it.
        if (cancelled.get() && listeners.remove(entry)) runSafely(listener);
        return () -> listeners.remove(entry);
    }

    public Registration interruptOnCancel(Thread thread) {
        Objects.requireNonNull(thread, "thread");
        return onCancel(thread::interrupt);
    }

    /// Idempotent.
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        for (Entry e : listeners) {
            if (listeners.remove(e)) runSafely(e.listener);   // remove == claim, so a racing onCancel cannot double-run it
        }
    }

    private static void runSafely(Runnable listener) {
        try {
            listener.run();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "cancellation listener threw", t);
        }
    }

    /// Identity wrapper so the same `Runnable` may be registered twice and released independently.
    private record Entry(Runnable listener) {
        @Override public boolean equals(Object o) { return this == o; }
        @Override public int hashCode()          { return System.identityHashCode(this); }
    }
}
