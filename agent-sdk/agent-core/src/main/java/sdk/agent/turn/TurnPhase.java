package sdk.agent.turn;

/// The turn's life: awaiting `Begin` → stream requested → streaming → assistant final →
/// tools settling → closed. Every [StepInput] is validated against the current phase.
public enum TurnPhase { OPENING, STREAM_REQUESTED, STREAMING, ASSISTANT_READY, TOOLS_RUNNING, CLOSED }
