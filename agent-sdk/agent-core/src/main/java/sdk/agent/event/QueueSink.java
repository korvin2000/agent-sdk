package sdk.agent.event;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// Pull side: a bounded blocking queue with a poison sentinel, driving a one-shot, single-consumer
/// `Stream<AgentEvent>`. Bounded and backpressured (the producer blocks when the consumer is slow);
/// an abandoned stream (closed by its consumer) stops the producer blocking; a late emit after
/// close is counted and dropped, never thrown.
public final class QueueSink implements EventSink {

    /// Wraps a producer failure delivered through [#fail] to the pulling consumer.
    public static final class EventStreamException extends RuntimeException {
        EventStreamException(Throwable cause) { super("event producer failed", cause); }
    }

    private static final Object EOS = new Object();
    private static final int DEFAULT_CAPACITY = 256;

    private final BlockingQueue<Object> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean abandoned = new AtomicBoolean();
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final AtomicInteger dropped = new AtomicInteger();

    public QueueSink() { this(DEFAULT_CAPACITY); }

    public QueueSink(int capacity) { this.queue = new LinkedBlockingQueue<>(capacity); }

    @Override public void emit(AgentEvent event) { offer(event); }

    @Override public void fail(Throwable cause) { offer(cause); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) enqueue(EOS);
    }

    /// Events that arrived after [#close] or after the consumer abandoned the stream.
    public int dropped() { return dropped.get(); }

    /// @throws IllegalStateException on a second call — the queue is single-consumer
    public Stream<AgentEvent> stream() {
        if (!consumed.compareAndSet(false, true)) throw new IllegalStateException("QueueSink.stream() is single-consumer");
        Iterator<AgentEvent> it = new Iterator<>() {
            private Object next;

            @Override public boolean hasNext() {
                if (next == null) next = take();
                return next != EOS;
            }

            @Override public AgentEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object o = next;
                next = null;
                if (o instanceof Throwable t) throw new EventStreamException(t);
                return (AgentEvent) o;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false)
                .onClose(() -> { abandoned.set(true); queue.clear(); });
    }

    private void offer(Object item) {
        if (closed.get() || abandoned.get()) { dropped.incrementAndGet(); return; }
        enqueue(item);
    }

    /// Backpressure applies only once somebody is pulling. Before that, the newest `capacity`
    /// items are kept and the oldest dropped, so a run nobody watches can never block on its own events.
    private void enqueue(Object item) {
        if (!consumed.get()) {
            while (!queue.offer(item)) {
                if (queue.poll() != null) dropped.incrementAndGet();
            }
            return;
        }
        try {
            queue.put(item);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();          // the run is being cancelled; the item is lost, not thrown
            dropped.incrementAndGet();
        }
    }

    private Object take() {
        try {
            return queue.take();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return EOS;
        }
    }
}
