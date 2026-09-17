package sdk.agent.event;

import java.util.List;

/// Where a run's events go. Three implementations ship, each one concern: [ListenerFanout]
/// (ordered, sequential, per-listener isolated), [QueueSink] (pull, bounded, blocking) and
/// [FlowAdapter] (a `Flow.Publisher` adapter, never the primitive).
public interface EventSink {

    void emit(AgentEvent event);

    /// Advisory, not terminal: lets a pull consumer see an exception rather than a silent end when
    /// something fails below the sink itself. `RunEnd` remains the one terminal event.
    void fail(Throwable cause);

    void close();

    EventSink NONE = new EventSink() {
        @Override public void emit(AgentEvent event) { }
        @Override public void fail(Throwable cause) { }
        @Override public void close() { }
    };

    /// Forwards to every sink in order.
    static EventSink tee(List<? extends EventSink> sinks) {
        List<EventSink> copy = List.copyOf(sinks);
        return new EventSink() {
            @Override public void emit(AgentEvent event) { copy.forEach(s -> s.emit(event)); }
            @Override public void fail(Throwable cause)  { copy.forEach(s -> s.fail(cause)); }
            @Override public void close()                { copy.forEach(EventSink::close); }
        };
    }
}
