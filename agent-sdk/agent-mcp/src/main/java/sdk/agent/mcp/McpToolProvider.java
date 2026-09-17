package sdk.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolCatalog;
import sdk.agent.tool.ToolProvider;

/// The one thing this module contributes to an agent. A `ToolProvider`, not a fixed tool list,
/// because the catalog changes on `tools/list_changed`: a **new** immutable snapshot is published
/// and the `volatile` reference is swapped. A registry is never mutated in place.
final class McpToolProvider implements ToolProvider, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpToolProvider.class.getName());

    /// Same-thread delivery: no executor to own, nothing to shut down beyond the publisher, and no
    /// virtual thread started outside `sdk.agent.concurrent`.
    private final SubmissionPublisher<ToolCatalog> updates =
            new SubmissionPublisher<>(Runnable::run, Flow.defaultBufferSize());

    private volatile ToolCatalog catalog = ToolCatalog.EMPTY;

    @Override public String id() { return McpExtension.ID; }

    @Override public ToolCatalog catalog() { return catalog; }

    @Override public Flow.Publisher<ToolCatalog> catalogUpdates() { return updates; }

    void publish(ToolCatalog next) {
        this.catalog = Objects.requireNonNull(next, "catalog");
        if (!updates.isClosed()) updates.submit(next);
    }

    @Override public void close() { updates.close(); }

    /// A **composed-name collision is a hard error at registration, naming both owners**. Only
    /// sanitisation or truncation can produce one, and silently overwriting would route calls to
    /// the wrong server — two servers exposing `search` would collapse, last writer wins.
    ///
    /// @param strict `true` at pool registration, where a collision must stop the agent starting;
    ///               `false` on a later `tools/list_changed`, where the newly arrived duplicate is
    ///               dropped with a logged error naming both owners and everything else keeps
    ///               working. Either way the collision is visible and nothing is misrouted.
    static ToolCatalog catalogOf(List<McpToolAdapter> adapters, boolean strict) {
        var byName = new LinkedHashMap<String, Tool<?>>();
        var owners = new LinkedHashMap<String, String>();
        for (McpToolAdapter adapter : adapters) {
            String name = adapter.name();
            String owner = adapter.server() + "/" + adapter.remoteName();
            String previous = owners.putIfAbsent(name, owner);
            if (previous != null) {
                String message = "MCP tool name collision on '" + name + "': " + previous + " and " + owner;
                if (strict) throw new IllegalStateException(message);
                LOG.log(System.Logger.Level.ERROR, "{0} — keeping {1}", message, previous);
                continue;
            }
            byName.put(name, adapter);
        }
        return new ToolCatalog(byName);
    }
}
