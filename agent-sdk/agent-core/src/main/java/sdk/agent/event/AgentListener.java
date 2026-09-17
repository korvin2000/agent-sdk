package sdk.agent.event;

/// One method, so a lambda or a method reference is a listener. A throw is caught by
/// [ListenerFanout] and routed to the agent's single `onListenerError`; a listener does not get a
/// second method for that, because two error paths is how one of them ends up untested.
@FunctionalInterface
public interface AgentListener {
    void onEvent(AgentEvent event);
}
