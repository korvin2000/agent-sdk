package sdk.agent.event;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// Pull-side observation for one run. It is deliberately lossy: producers never wait for a slow
/// consumer, and overflow discards the oldest observation. `RunResult` remains the durable result.
public final class QueueSink implements EventSink {

    private static final int DEFAULT_CAPACITY = 256;

    private final ArrayDeque<AgentEvent> queue;
    private final int capacity;
    private boolean closed;
    private boolean claimed;
    private boolean abandoned;
    private int dropped;

    public QueueSink() { this(DEFAULT_CAPACITY); }

    public QueueSink(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        queue = new ArrayDeque<>(capacity);
    }

    @Override public synchronized void emit(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed || abandoned) {
            dropped++;
            return;
        }
        if (queue.size() == capacity) {
            queue.removeFirst();
            dropped++;
        }
        queue.addLast(event);
        notifyAll();
    }

    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            notifyAll();
        }
    }

    /// Events discarded after closure/abandonment or evicted by bounded observation.
    public synchronized int dropped() { return dropped; }

    /// @throws IllegalStateException on a second call — this is a single-consumer stream
    public Stream<AgentEvent> stream() {
        synchronized (this) {
            if (claimed) throw new IllegalStateException("QueueSink.stream() is single-consumer");
            claimed = true;
        }
        Iterator<AgentEvent> it = new Iterator<>() {
            private AgentEvent next;
            private boolean finished;

            @Override public boolean hasNext() {
                if (!finished && next == null) {
                    next = take();
                    if (next == null) finished = true;
                }
                return !finished;
            }

            @Override public AgentEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                AgentEvent result = next;
                next = null;
                return result;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false)
                .onClose(this::abandon);
    }

    private synchronized AgentEvent take() {
        while (queue.isEmpty() && !closed && !abandoned) {
            try {
                wait();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return abandoned || queue.isEmpty() ? null : queue.removeFirst();
    }

    private synchronized void abandon() {
        if (!abandoned) {
            abandoned = true;
            queue.clear();
            notifyAll();
        }
    }
}
