package sdk.agent.concurrent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/// `StructuredTaskScope`-shaped structured concurrency without preview features. One file, so the
/// swap when JEP 525 finalises is a single edit. Deliberately smaller than the JDK API: there is no
/// unbounded `join()` (every wait in an agent has a deadline) and no `isDone()` (it invites a poll
/// loop where `get()` on a virtual thread is simpler).
///
/// [#fork] **rebinds [RunScope#CURRENT]** into the task — the whole reason raw virtual threads are
/// banned outside this package. [#close] cancels, waits at most the grace period and then
/// **abandons** what is still running: a task that ignores interrupt leaks one virtual thread by
/// design, and [Fork#open(Duration, Consumer)] `onLeak` is told its name. A `close()` that could
/// hang is how a decorative timeout becomes a hang inside our own code.
public final class Fork implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Fork.class.getName());
    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(2);

    public interface Handle<T> {
        T get() throws ExecutionException, InterruptedException;
        void cancel();
    }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Task<?>> tasks = new CopyOnWriteArrayList<>();
    private final AtomicInteger names = new AtomicInteger();
    private final Duration closeGrace;
    private final Consumer<String> onLeak;

    private Fork(Duration closeGrace, Consumer<String> onLeak) {
        this.closeGrace = Objects.requireNonNull(closeGrace, "closeGrace");
        this.onLeak = Objects.requireNonNull(onLeak, "onLeak");
    }

    public static Fork open() { return open(DEFAULT_GRACE); }

    public static Fork open(Duration closeGrace) {
        return open(closeGrace, name -> LOG.log(System.Logger.Level.WARNING, "abandoned unresponsive task {0}", name));
    }

    public static Fork open(Duration closeGrace, Consumer<String> onLeak) { return new Fork(closeGrace, onLeak); }

    public <T> Handle<T> fork(Callable<T> task) { return fork("task-" + names.incrementAndGet(), task); }

    /// The most important six lines in the module: capture the scope at fork time and rebind it
    /// in the task. `Carrier.call` takes a `ScopedValue.CallableOp`, so `task::call` is required —
    /// `.call(task)` with a `Callable` variable does not compile.
    public <T> Handle<T> fork(String name, Callable<T> task) {
        Objects.requireNonNull(task, "task");
        RunScope captured = RunScope.currentIfBound().orElse(null);   // ScopedValue.orElse(null) throws NPE
        var finished = new AtomicBoolean();
        Future<T> future = executor.submit(() -> {
            try {
                return captured == null ? task.call() : ScopedValue.where(RunScope.CURRENT, captured).call(task::call);
            } finally {
                finished.set(true);
            }
        });
        var handle = new Task<>(name, future, finished);
        tasks.add(handle);
        return handle;
    }

    public Handle<Void> run(Runnable task) {
        Objects.requireNonNull(task, "task");
        return fork(() -> { task.run(); return null; });
    }

    /// Waits for every task started so far to finish (successfully or not) or for the deadline.
    /// @throws TimeoutException if any task is still running at the deadline
    public void joinUntil(Instant deadline) throws InterruptedException, TimeoutException {
        for (Task<?> t : tasks) {
            long remainingNanos = Duration.between(Instant.now(), deadline).toNanos();
            if (remainingNanos <= 0) {
                if (!t.finished.get()) throw new TimeoutException("task " + t.name + " did not finish by the deadline");
                continue;
            }
            try {
                t.future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException | CancellationException _) {
                // finished, one way or another — join means "done", not "succeeded"
            }
        }
    }

    /// Interrupts every unfinished task.
    public void cancelAll() { tasks.forEach(Task::cancel); }

    /// `cancelAll()`, then wait at most the grace period, then abandon. Never hangs.
    @Override public void close() {
        cancelAll();
        executor.shutdownNow();
        boolean quiet;
        try {
            quiet = executor.awaitTermination(closeGrace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            quiet = false;
        }
        if (!quiet) {
            tasks.stream().filter(t -> !t.finished.get()).forEach(t -> onLeak.accept(t.name));
        }
    }

    private record Task<T>(String name, Future<T> future, AtomicBoolean finished) implements Handle<T> {
        @Override public T get() throws ExecutionException, InterruptedException { return future.get(); }
        @Override public void cancel() { future.cancel(true); }
    }
}
