package sdk.agent.hook;

/// The six streak tiers [TurnGuard] watches. The first three are windows over recent tool calls,
/// the last three are consecutive-turn counters.
public enum Thrash { REPEATED, SAME_TOOL, DOMINANT, EMPTY, MALFORMED, TRUNCATED }
