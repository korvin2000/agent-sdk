package sdk.agent.tool;

/// Every model-facing string the engine itself can put into a tool result, in one place. Five are
/// verbatim from pi-mono, kon and mini-swe-agent (MIT — see THIRD-PARTY-NOTICES.md); they are
/// prompt text tuned against real models, and the loop is where they belong.
public final class ToolMessages {

    private ToolMessages() { }

    /// pi-mono `agent-loop.ts:483`, verbatim. `%s` is the tool name.
    public static final String TOOL_NOT_FOUND = "Tool %s not found";

    /// pi-mono `agent-loop.ts:504`, verbatim. Used when a `beforeToolCall` veto gives no reason.
    public static final String BLOCKED = "Tool execution was blocked";

    /// Ours: the tool exists but was not in the request the model answered (a hook stripped it).
    public static final String NOT_ADVERTISED = "Tool %s was not offered in this turn";

    /// Ours — a deliberate string, not the accidental "The operation was aborted" pi leaks.
    public static final String CANCELLED = "Tool execution was cancelled by the user.";

    /// mini-swe-agent `actions_toolcall.py:88-89`, verbatim: the padding for a slot that never ran.
    public static final String NOT_EXECUTED = "action was not executed";

    /// kon `turn.py:227-228` and pi-mono `bash.ts:353`: a provider may reject an empty tool result.
    public static final String NO_OUTPUT = "(no output)";

    /// kon `turn.py:145-204`, verbatim.
    public static final String ARGS_INVALID_JSON =
            "Tool call arguments were incomplete or invalid JSON; skipping execution instead of running with empty arguments.";
}
