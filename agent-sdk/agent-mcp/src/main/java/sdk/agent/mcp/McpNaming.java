package sdk.agent.mcp;

import java.util.regex.Pattern;

import sdk.agent.tool.ToolNaming;

/// The agent-facing name of a remote tool is **`mcp__<server>__<tool>`** — double underscore,
/// because most providers constrain tool names to `^[A-Za-z0-9_-]{1,64}$` and dots are unusable.
///
/// The mapping `agentName → (server, remoteName)` is **authoritative and is never reverse-parsed**:
/// a `split("_")` reparse is wrong for any server or tool name containing an underscore. The owner
/// of the mapping is [McpToolAdapter], which keeps both halves as fields.
public final class McpNaming {

    private static final Pattern ALLOWED = Pattern.compile("[^A-Za-z0-9_-]");

    private McpNaming() { }

    /// Sanitised ONCE, at config load, not at call time. A server whose sanitised name collides
    /// with another's is a hard error before any connection is attempted ([McpConnectionPool]).
    public static String sanitizeServer(String raw) {
        if (raw == null) throw new IllegalArgumentException("server name must not be null");
        String s = ALLOWED.matcher(raw).replaceAll("_");
        if (s.isBlank()) throw new IllegalArgumentException("server name sanitises to empty: " + raw);
        return s;
    }

    /// Builds the MCP spelling and hands it to core. [ToolNaming#compose(String)] owns the 64-char
    /// cap, the truncation to 57 and the 6-hex SHA-256 suffix, so the rule exists in exactly one
    /// place and an MCP name cannot drift from a skill's or a host's.
    public static String compose(String server, String tool) {
        return ToolNaming.compose("mcp__" + server + "__" + tool);
    }
}
