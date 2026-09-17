package sdk.agent.mcp;

import java.util.Objects;
import java.util.Optional;

/// What happened to one server during startup, returned in **declaration order** so a host can
/// render a stable report regardless of which connect finished first.
///
/// @param name          the raw configured name
/// @param server        the sanitised name used in `mcp__<server>__<tool>`, absent if disabled
/// @param connected     `initialize()` **and** `tools/list` both succeeded
/// @param toolCount     tools registered after [McpServerConfig#toolFilter] was applied
/// @param error         why it failed, absent on success
public record McpInitResult(String name, Optional<String> server, boolean connected,
                            int toolCount, Optional<String> error) {

    public McpInitResult {
        Objects.requireNonNull(name, "name");
        server = server == null ? Optional.empty() : server;
        error = error == null ? Optional.empty() : error;
    }

    static McpInitResult ok(String name, String server, int toolCount) {
        return new McpInitResult(name, Optional.of(server), true, toolCount, Optional.empty());
    }

    static McpInitResult failed(String name, String server, String error) {
        return new McpInitResult(name, Optional.ofNullable(server), false, 0, Optional.of(error));
    }

    static McpInitResult disabled(String name) {
        return new McpInitResult(name, Optional.empty(), false, 0, Optional.of("disabled"));
    }
}
