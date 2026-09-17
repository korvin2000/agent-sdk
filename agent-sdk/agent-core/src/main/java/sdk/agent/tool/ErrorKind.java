package sdk.agent.tool;

/// Why a tool call produced an error result. Three facts that must stay distinct:
/// `EXECUTION_FAILED` — the funnel caught something the tool did not expect;
/// `TOOL_REPORTED` — the tool ran to completion and reported failure as data (a failed compile is
/// the answer, not a crash); `UNAVAILABLE` — infrastructure, not the tool.
public enum ErrorKind {
    TOOL_NOT_FOUND,
    INVALID_ARGUMENTS,
    BLOCKED,
    EXECUTION_FAILED,
    TOOL_REPORTED,
    CANCELLED,
    TIMED_OUT,
    HOOK_FAILED,
    UNAVAILABLE,
    /// The slot was padded because the call never ran (abort, veto, or cap).
    NOT_EXECUTED
}
