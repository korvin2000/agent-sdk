package sdk.agent;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.concurrent.Fork;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStream;
import sdk.agent.provider.LlmStreamEvent;

/// One virtual thread opens and drains a provider stream. The bounded queue preserves provider
/// order while a separate completion state lets cancellation wake consumers without needing space
/// for a sentinel.
final class ProviderPump implements AutoCloseable {

    private static final int CAPACITY = 256;

    private final Object monitor = new Object();
    private final ArrayDeque<LlmStreamEvent> events = new ArrayDeque<>(CAPACITY);
    private final LlmProvider provider;
    private final LlmRequest request;
    private final Cancellation cancellation;
    private final Fork fork = Fork.open();
    private final Fork.Handle<Void> cleanup;
    private final Fork.Handle<Void> producer;
    private final Cancellation.Registration cancellationRegistration;

    private LlmStream stream;
    private Throwable failure;
    private boolean opened;
    private boolean complete;
    private boolean cleanupStarted;
    private boolean producerFinished;
    private volatile boolean closed;
    private boolean cleanupRequested;
    private boolean closeClaimed;

    ProviderPump(LlmProvider provider, LlmRequest request, Cancellation cancellation) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.request = Objects.requireNonNull(request, "request");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        cleanup = fork.run(this::cleanup);
        producer = fork.run(this::produce);
        cancellationRegistration = cancellation.onCancel(this::cancelled);
    }

    /// Waits until the provider opened its stream. Opening failures use the same channel as reads.
    void awaitOpen() throws IOException, InterruptedException {
        synchronized (monitor) {
            while (!opened && failure == null && !complete && !stopping()) monitor.wait();
            if (stopping()) throw cancelledException();
            if (opened) return;
            if (failure != null) throw asIo(failure);
            throw new IOException("provider stream ended before opening");
        }
    }

    /// Returns `null` at end of stream.
    /// @throws TimeoutException when `idleMillis` elapses with no event
    LlmStreamEvent next(OptionalLong idleMillis) throws InterruptedException, TimeoutException, IOException {
        long deadline = idleMillis.isPresent()
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleMillis.getAsLong()) : 0;
        synchronized (monitor) {
            for (;;) {
                if (stopping()) throw cancelledException();
                LlmStreamEvent event = events.pollFirst();
                if (event != null) {
                    monitor.notifyAll();
                    return event;
                }
                if (failure != null) throw asIo(failure);
                if (complete) return null;
                if (idleMillis.isEmpty()) {
                    monitor.wait();
                    continue;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("no provider event within " + idleMillis.getAsLong() + " ms");
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
        }
    }

    @Override public void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            cleanupRequested = true;
            monitor.notifyAll();
        }
        cancellationRegistration.close();
        producer.cancel();
        fork.close();                                   // bounded; never hangs
    }

    private void produce() {
        LlmStream openedStream = null;
        try {
            awaitCleanup();
            if (stopping()) return;
            openedStream = provider.stream(request, cancellation);
            if (openedStream == null) throw new IOException("provider returned no stream");

            boolean closeLate;
            synchronized (monitor) {
                stream = openedStream;
                closeLate = stopping();
                if (!closeLate) {
                    opened = true;
                }
                monitor.notifyAll();
            }
            if (closeLate) {
                closeDirectly(openedStream);
                return;
            }

            while (!stopping()) {
                LlmStreamEvent event = openedStream.next();
                if (stopping()) return;
                if (event == null) {
                    complete();
                    return;
                }
                if (!publish(event) || event.terminal()) return;
            }
        } catch (Throwable t) {
            if (!stopping()) fail(t);
        } finally {
            requestStreamClose();
            if (openedStream != null) closeDirectly(openedStream);
            synchronized (monitor) {
                producerFinished = true;
                monitor.notifyAll();
            }
        }
    }

    private boolean publish(LlmStreamEvent event) throws InterruptedException {
        synchronized (monitor) {
            while (events.size() == CAPACITY && !stopping()) monitor.wait();
            if (stopping()) return false;
            events.addLast(event);
            if (event.terminal()) complete = true;
            monitor.notifyAll();
            return true;
        }
    }

    private void complete() {
        synchronized (monitor) {
            complete = true;
            monitor.notifyAll();
        }
    }

    private void fail(Throwable t) {
        synchronized (monitor) {
            failure = t;
            complete = true;
            monitor.notifyAll();
        }
    }

    private void cancelled() {
        synchronized (monitor) {
            cleanupRequested = true;
            monitor.notifyAll();
        }
        producer.cancel();
    }

    private boolean stopping() { return closed || cancellation.isCancelled(); }

    private InterruptedException cancelledException() {
        return new InterruptedException(closed ? "provider pump closed" : "provider stream cancelled");
    }
    private void cleanup() {
        LlmStream owned;
        synchronized (monitor) {
            cleanupStarted = true;
            monitor.notifyAll();
            for (;;) {
                try {
                    if (closeClaimed) return;
                    if (stream != null && cleanupRequested) {
                        closeClaimed = true;
                        owned = stream;
                        break;
                    }
                    if (producerFinished || complete || (stopping() && stream == null)) return;
                    monitor.wait();
                } catch (InterruptedException _) {
                    // Re-check ownership and stopping state before abandoning cleanup.
                }
            }
        }
        owned.close();
    }

    private void awaitCleanup() throws InterruptedException {
        synchronized (monitor) {
            while (!cleanupStarted) monitor.wait();
        }
    }

    private void requestStreamClose() {
        synchronized (monitor) {
            cleanupRequested = true;
            monitor.notifyAll();
        }
    }
    private void closeDirectly(LlmStream owned) {
        synchronized (monitor) {
            if (closeClaimed) return;
            closeClaimed = true;
        }
        owned.close();                                  // late open or cleanup-worker fallback
    }

    private static IOException asIo(Throwable t) {
        return switch (t) {
            case IOException io -> io;
            case RuntimeException re -> throw re;
            case Error err -> throw err;
            default -> new IOException(t);
        };
    }
}
