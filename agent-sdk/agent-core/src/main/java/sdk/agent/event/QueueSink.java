package sdk.agent.event;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// Pull side: a bounded buffer driving a one-shot, single-consumer `Stream<AgentEvent>`. It is
/// deliberately **lossy**: the producer never waits for a consumer, and on overflow the oldest
/// event is dropped — so a run can never be stalled by an observer that stopped reading. The
/// newest `capacity` events are kept, which is what keeps `RunEnd` for a late consumer;
/// `RunResult` and the transcript stay authoritative. A late emit after close is counted and
/// dropped, never thrown.
public final class QueueSink implements EventSink {

    private static final int DEFAULT_CAPACITY = 256;

    private final ArrayDeque<AgentEvent> queue = new ArrayDeque<>();
    private final int capacity;
    private boolean closed, claimed, abandoned;
    private int dropped;

    public QueueSink() { this(DEFAULT_CAPACITY); }

    public QueueSink(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        this.capacity = capacity;
    }

    @Override public synchronized void emit(AgentEvent event) {
        if (closed || abandoned) { dropped++; return; }
        if (queue.size() == capacity) { queue.removeFirst(); dropped++; }
        queue.addLast(event);
        notifyAll();
    }

    @Override public synchronized void close() {
        closed = true;
        notifyAll();
    }

    /// Events that arrived after [#close], after the consumer abandoned the stream, or that were
    /// evicted by a full buffer.
    public synchronized int dropped() { return dropped; }

    /// @throws IllegalStateException on a second call — the queue is single-consumer
    public Stream<AgentEvent> stream() {
        synchronized (this) {
            if (claimed) throw new IllegalStateException("QueueSink.stream() is single-consumer");
            claimed = true;
        }
        Iterator<AgentEvent> it = new Iterator<>() {
            private AgentEvent next;

            @Override public boolean hasNext() {
                if (next == null) next = take();
                return next != null;
            }

            @Override public AgentEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                AgentEvent e = next;
                next = null;
                return e;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false)
                .onClose(this::abandon);
    }

    /// Blocks for the next event; `null` once the sink is closed and drained, or abandoned.
    private synchronized AgentEvent take() {
        while (queue.isEmpty() && !closed && !abandoned) {
            try {
                wait();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return abandoned ? null : queue.pollFirst();
    }

    private synchronized void abandon() {
        abandoned = true;
        queue.clear();
        notifyAll();
    }
}
