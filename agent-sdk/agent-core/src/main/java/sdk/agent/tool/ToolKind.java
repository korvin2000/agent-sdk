package sdk.agent.tool;

/// Parallel eligibility, decided locally from our own registration. A maximal run of
/// `READ_ONLY` calls forms one concurrent batch; anything `MUTATING` runs alone.
public enum ToolKind { READ_ONLY, MUTATING }
