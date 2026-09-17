package sdk.agent.event;

import sdk.agent.message.StopReason;

/// How a run ended. One uniform terminal shape for every stop reason; you cannot read the outcome
/// without pattern-matching the failure case, so failure is never silent.
public sealed interface RunOutcome {

    record Completed(StopReason reason) implements RunOutcome { }

    record Aborted() implements RunOutcome { }

    record Failed(StopReason reason, String message, Throwable cause) implements RunOutcome { }

    record LimitExceeded(Limit limit, String detail) implements RunOutcome { }

    /// Every member has exactly one enforcement site in the engine.
    enum Limit { MAX_TURNS, MAX_TOOL_CALLS, WALL_CLOCK, THRASH }

    default boolean isSuccess() { return this instanceof Completed; }
}
