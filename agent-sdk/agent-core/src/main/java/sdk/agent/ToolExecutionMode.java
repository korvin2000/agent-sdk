package sdk.agent;

/// `SEQUENTIAL` is not a second scheduler: it is the batching rule with the batch size pinned to
/// one — the escape hatch for a host unsure whether its tools are truly read-only.
public enum ToolExecutionMode { PARALLEL, SEQUENTIAL }
