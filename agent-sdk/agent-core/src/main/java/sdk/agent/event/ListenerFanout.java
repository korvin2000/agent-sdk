package sdk.agent.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/// Ordered, sequential, multi-consumer fan-out with per-listener isolation. Sequential delivery is a
/// feature: it gives listeners backpressure over the loop (a slow renderer slows the agent rather
/// than dropping frames) and a deterministic order. A throwing listener is reported and skipped —
/// a UI render error must not corrupt the conversation.
public final class ListenerFanout implements EventSink {

    private static final System.Logger LOG = System.getLogger(ListenerFanout.class.getName());

    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();
    private final BiConsumer<AgentListener, Throwable> onListenerError;

    public ListenerFanout(BiConsumer<AgentListener, Throwable> onListenerError) {
        this.onListenerError = Objects.requireNonNull(onListenerError, "onListenerError");
    }

    /// Reports listener failures at `WARNING` through `System.Logger`.
    public static ListenerFanout logging() {
        return new ListenerFanout((l, t) -> LOG.log(System.Logger.Level.WARNING, "agent listener " + l + " threw", t));
    }

    public Subscription subscribe(AgentListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public int size() { return listeners.size(); }

    @Override public void emit(AgentEvent event) {
        for (AgentListener l : listeners) {
            try {
                l.onEvent(event);
            } catch (Throwable t) {
                onListenerError.accept(l, t);
            }
        }
    }

    /// Agent-level: listeners outlive runs.
    @Override public void close() { }
}
