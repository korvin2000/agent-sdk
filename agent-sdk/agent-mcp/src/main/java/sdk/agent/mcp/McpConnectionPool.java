package sdk.agent.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import sdk.agent.concurrent.Fork;

/// Every connection this module owns. Connects in parallel, fault-isolated, and reports in
/// **declaration order** rather than completion order.
///
/// **Atomic registration**: a server appears in the tool list only after `initialize()` *and*
/// `tools/list` have both succeeded. A half-connected server is closed by [McpConnection#open] and
/// never registered; a connection that completes after the connect budget expired is closed by
/// its own attempt; a strict catalog collision closes every admitted connection before it throws.
/// So no path leaks a child process.
final class McpConnectionPool implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpConnectionPool.class.getName());
    private static final Duration CONNECT_BUDGET = Duration.ofSeconds(60);
    private static final Duration CONNECT_CLOSE_GRACE = Duration.ofSeconds(5);

    /// Immutable after construction, so a `tools/list_changed` arriving on a notification thread
    /// can iterate it while the host is tearing the pool down.
    private final SequencedMap<String, McpConnection> connections;
    private final List<McpInitResult> results;
    private final McpToolProvider provider = new McpToolProvider();
    private boolean closed;                                                  // guarded by this

    private McpConnectionPool(SequencedMap<String, McpConnection> connections, List<McpInitResult> results) {
        this.connections = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(connections));
        this.results = List.copyOf(results);
    }

    static McpConnectionPool connect(List<McpServerConfig> servers) {
        Objects.requireNonNull(servers, "servers");
        // Sanitised ONCE, here, before any connection is attempted: a collision is a hard error
        // now rather than a silently shadowed server later.
        SequencedMap<String, McpServerConfig> byServer = sanitizedNames(servers);

        var attempts = new LinkedHashMap<String, Attempt>();
        try (var fork = Fork.open(CONNECT_CLOSE_GRACE)) {
            byServer.forEach((server, config) -> {
                var attempt = new Attempt();
                attempts.put(server, attempt);
                fork.fork("mcp-connect-" + server, () -> { attempt.run(config, server); return null; });
            });
            try {
                fork.joinUntil(Instant.now().plus(CONNECT_BUDGET));
            } catch (TimeoutException _) {
                LOG.log(System.Logger.Level.WARNING, "MCP connect budget of {0}s expired", CONNECT_BUDGET.toSeconds());
                attempts.values().forEach(Attempt::abandon);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                attempts.values().forEach(Attempt::abandon);
            }
        }

        var live = new LinkedHashMap<String, McpConnection>();
        var results = new ArrayList<McpInitResult>(servers.size());
        for (McpServerConfig config : servers) {
            if (!config.enabled()) {
                results.add(McpInitResult.disabled(config.name()));
                continue;
            }
            String server = McpNaming.sanitizeServer(config.name());
            Attempt attempt = attempts.get(server);
            McpConnection connection = attempt.connection();
            if (connection == null) {
                results.add(McpInitResult.failed(config.name(), server, attempt.error()));
            } else {
                live.put(server, connection);
                results.add(McpInitResult.ok(config.name(), server, connection.tools().size()));
            }
        }

        var pool = new McpConnectionPool(live, results);
        try {
            pool.connections.values().forEach(c -> c.onToolsChanged(() -> pool.republish(false)));
            pool.republish(true);
        } catch (RuntimeException e) {                                   // a strict collision must not leak the children
            pool.closeAll();
            throw e;
        }
        return pool;
    }

    McpToolProvider provider() { return provider; }

    List<McpInitResult> results() { return results; }

    /// Rebuilds the namespaced tool list from every registered connection and publishes a **new**
    /// immutable snapshot; the previous one is never mutated. A late `list_changed` after close is ignored.
    private synchronized void republish(boolean strict) {
        if (closed) return;
        var adapters = new ArrayList<McpToolAdapter>();
        connections.values().forEach(c -> c.tools().forEach(t -> adapters.add(new McpToolAdapter(c, t))));
        provider.publish(McpToolProvider.catalogOf(adapters, strict));
    }

    /// Resilient disconnect, in reverse connect order: the tool list is emptied first so no stale
    /// tool survives the teardown, then every client is closed in its own try/catch.
    void closeAll() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            provider.publish(List.of());
        }
        int done = 0;
        for (McpConnection connection : connections.reversed().values()) {
            try {
                connection.close();
                done++;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "closing MCP server " + connection.server() + " threw", e);
            }
        }
        LOG.log(System.Logger.Level.DEBUG, "closed {0} of {1} MCP connections", done, connections.size());
    }

    @Override public void close() { closeAll(); }

    // ---- startup helpers -------------------------------------------------------------------

    /// @throws IllegalArgumentException naming **both** raw names if two sanitise alike
    static SequencedMap<String, McpServerConfig> sanitizedNames(List<McpServerConfig> servers) {
        var byServer = new LinkedHashMap<String, McpServerConfig>();
        for (McpServerConfig config : servers) {
            if (!config.enabled()) continue;
            String server = McpNaming.sanitizeServer(config.name());
            McpServerConfig previous = byServer.putIfAbsent(server, config);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "MCP server name collision on '" + server + "': '" + previous.name()
                                + "' and '" + config.name() + "' sanitise to the same name");
            }
        }
        return byServer;
    }

    /// One server's connect attempt. The slot is claimed exactly once — by the connection, the
    /// failure, or the expired budget — so a connection that arrives after abandonment is closed
    /// by the thread that made it rather than leaked.
    private static final class Attempt {
        private static final String ABANDONED = "connect budget expired";

        private final AtomicReference<Object> slot = new AtomicReference<>();      // McpConnection | Throwable | ABANDONED

        void run(McpServerConfig config, String server) {
            try {
                McpConnection connection = McpConnection.open(config, server);
                if (!slot.compareAndSet(null, connection)) connection.close();
            } catch (RuntimeException e) {
                slot.compareAndSet(null, e);
            }
        }

        void abandon() { slot.compareAndSet(null, ABANDONED); }

        McpConnection connection() { return slot.get() instanceof McpConnection c ? c : null; }

        String error() {
            return switch (slot.get()) {
                case Throwable t -> t.getMessage() == null || t.getMessage().isBlank()
                        ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + t.getMessage();
                case String s -> s;
                default -> "";
            };
        }
    }
}
