package sdk.agent.tool;

/// Model-facing engine tool-result strings. Attributed prompt text is retained where the
/// corresponding behavior remains useful; see THIRD-PARTY-NOTICES.md.
public final class ToolMessages {

    private ToolMessages() { }

    /// pi-mono `agent-loop.ts:483`, verbatim. `%s` is the tool name.
    public static final String TOOL_NOT_FOUND = "Tool %s not found";

    /// pi-mono `agent-loop.ts:504`, verbatim. Used when a `beforeToolCall` veto gives no reason.
    public static final String BLOCKED = "Tool execution was blocked";

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
