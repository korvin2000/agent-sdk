package sdk.agent;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.concurrent.Fork;
import sdk.agent.provider.LlmStream;
import sdk.agent.provider.LlmStreamEvent;

/// One virtual thread drains the provider stream into a bounded queue; the sentinel is enqueued in
/// a `finally` on every path so the consumer can never block forever. Idle timeout, backpressure
/// and clean close-on-cancel from one primitive. Per turn: the cancellation registration is held
/// and released in [#close], so a hundred turns do not accumulate a hundred listeners.
final class ProviderPump implements AutoCloseable {

    private static final Object EOS = new Object();

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(256);
    private final LlmStream stream;
    private final Fork fork;
    private final Cancellation.Registration cancelRegistration;

    ProviderPump(LlmStream stream, Cancellation cancel) {
        this.stream = stream;
        this.fork = Fork.open();
        this.cancelRegistration = cancel.onCancel(stream::close);
        fork.run(() -> {
            try {
                LlmStreamEvent e;
                while ((e = stream.next()) != null) queue.put(e);
            } catch (Throwable t) {
                put(t);
            } finally {
                put(EOS);
            }
        });
    }

    /// Returns `null` at end of stream.
    /// @throws TimeoutException when `idleMillis` elapses with no event
    LlmStreamEvent next(OptionalLong idleMillis) throws InterruptedException, TimeoutException, IOException {
        Object o = idleMillis.isPresent() ? queue.poll(idleMillis.getAsLong(), TimeUnit.MILLISECONDS) : queue.take();
        if (o == null) throw new TimeoutException("no provider event within " + idleMillis.getAsLong() + " ms");
        if (o == EOS) return null;
        if (o instanceof Throwable t) throw asIo(t);
        return (LlmStreamEvent) o;
    }

    @Override public void close() {
        cancelRegistration.close();
        stream.close();
        fork.close();                                   // bounded; never hangs
    }

    private void put(Object item) {
        try {
            queue.put(item);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
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
