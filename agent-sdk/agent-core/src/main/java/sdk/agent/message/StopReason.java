package sdk.agent.message;

/// Why an assistant turn ended. `ERROR` and `ABORTED` are the two terminal failures; a run never
/// throws for either.
public enum StopReason { STOP, LENGTH, TOOL_USE, ERROR, ABORTED }
