package sdk.agent.event;

import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;

/// A `Flow.Publisher` **adapter** for hosts that live in a reactive world. Never the primitive: the
/// `request(n)` protocol is a poor fit for one bounded queue between two threads.
public final class FlowAdapter implements EventSink, Flow.Publisher<AgentEvent> {

    private final SubmissionPublisher<AgentEvent> publisher;

    public FlowAdapter() { this(ForkJoinPool.commonPool(), Flow.defaultBufferSize()); }

    public FlowAdapter(Executor executor, int maxBufferCapacity) {
        this.publisher = new SubmissionPublisher<>(executor, maxBufferCapacity);
    }

    /// Blocks while every subscriber's buffer is saturated — backpressure, not drops.
    @Override public void emit(AgentEvent event) { publisher.submit(event); }

    @Override public void fail(Throwable cause) { publisher.closeExceptionally(cause); }

    @Override public void close() { publisher.close(); }

    @Override public void subscribe(Flow.Subscriber<? super AgentEvent> subscriber) { publisher.subscribe(subscriber); }
}
