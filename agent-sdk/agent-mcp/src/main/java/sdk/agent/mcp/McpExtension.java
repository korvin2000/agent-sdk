package sdk.agent.mcp;

import java.util.List;
import java.util.Objects;

import sdk.agent.spi.Contributions;
import sdk.agent.spi.Extension;

/// Optional MCP integration contributing a dynamic tool catalog, not core hooks or codecs.
/// Remote schemas remain opaque; structured results are model-visible JSON and host details.
///
/// Connections own paginated discovery, coalesced refresh work, and transport shutdown. Failed
/// refreshes retain the last complete catalog; closing detaches it before cancelling owned work.
/// SDK shutdown is bounded locally but does not guarantee force-killing a hostile stdio child.
///
/// Reactor and the ServiceLoader-resolved Jackson mapper remain dependencies of this optional
/// module. It uses the automatic module name `sdk.agent.mcp`, not a core module dependency.
public final class McpExtension implements Extension {

    static final String ID = "sdk.agent.mcp";

    private final McpConnectionPool pool;

    /// Connects in the constructor: servers are contacted in parallel, fault-isolated, and a
    /// server that fails simply contributes no tools. A **sanitised server-name collision is a
    /// hard error here**, before any process is spawned or socket opened.
    public McpExtension(List<McpServerConfig> servers) {
        this.pool = McpConnectionPool.connect(Objects.requireNonNull(servers, "servers"));
    }

    @Override public String id() { return ID; }

    /// A `ToolProvider`, not a fixed tool list: the catalog changes on `tools/list_changed`.
    @Override public Contributions contributions() {
        return Contributions.builder().toolProvider(pool.provider()).build();
    }

    /// One entry per configured server, in **declaration order**.
    public List<McpInitResult> initResults() { return pool.results(); }

    @Override public void close() { pool.closeAll(); }
}
