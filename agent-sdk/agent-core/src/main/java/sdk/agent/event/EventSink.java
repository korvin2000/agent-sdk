package sdk.agent.event;

import java.util.List;

/// Where a run's events go. Two implementations ship: [ListenerFanout] (push, ordered, per-listener
/// isolated) and [QueueSink] (pull, bounded, blocking). `RunEnd` is the one terminal event and
/// carries the outcome, so there is no separate failure channel.
public interface EventSink {

    void emit(AgentEvent event);

    void close();

    EventSink NONE = new EventSink() {
        @Override public void emit(AgentEvent event) { }
        @Override public void close() { }
    };

    /// Forwards to every sink in order.
    static EventSink tee(List<? extends EventSink> sinks) {
        List<EventSink> copy = List.copyOf(sinks);
        return new EventSink() {
            @Override public void emit(AgentEvent event) { copy.forEach(s -> s.emit(event)); }
            @Override public void close()                { copy.forEach(EventSink::close); }
        };
    }
}
