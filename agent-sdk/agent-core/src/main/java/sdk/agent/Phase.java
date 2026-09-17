package sdk.agent;

/// Run-level phases. One `RunEngine.advance()` performs exactly one row of the transition table
/// and returns only at a durable checkpoint.
public enum Phase { NEW, TURN_OPENING, ASSISTANT_READY, TOOLS_RUNNING, TURN_CLOSED, FOLLOW_UP, FINISHED }
