package sdk.agent.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import sdk.agent.concurrent.Fork;
import sdk.agent.tool.Tool;

/// Every connection this module owns. Connects in parallel, fault-isolated, and reports in
/// **declaration order** rather than completion order.
///
/// **Atomic registration**: a server appears in the tool list only after `initialize()` *and*
/// `tools/list` have both succeeded. A half-connected server is closed by [McpConnection#open] and
/// never registered, so a handshake-ok/list-failed stdio server cannot leak its child process.
final class McpConnectionPool implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpConnectionPool.class.getName());
    private static final Duration CONNECT_BUDGET = Duration.ofSeconds(60);
    private static final Duration CONNECT_CLOSE_GRACE = Duration.ofSeconds(5);

    /// Immutable after construction, so a `tools/list_changed` arriving on a notification thread
    /// can iterate it while the host is tearing the pool down.
    private final SequencedMap<String, McpConnection> connections;
    private final List<McpInitResult> results;
    private final McpToolProvider provider = new McpToolProvider();
    private final AtomicBoolean closed = new AtomicBoolean();

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
            byServer.forEach((server, config) -> attempts.put(server,
                    new Attempt(server, fork.fork("mcp-connect-" + server, () -> McpConnection.open(config, server)))));
            joinQuietly(fork);
        }

        var live = new LinkedHashMap<String, McpConnection>();
        attempts.forEach((server, attempt) -> {
            McpConnection connection = attempt.result();
            if (connection != null) live.put(server, connection);
        });

        var pool = new McpConnectionPool(live, collect(servers, attempts));
        pool.connections.values().forEach(c -> c.onToolsChanged(() -> pool.republish(false)));
        pool.republish(true);
        return pool;
    }

    List<Tool<?>> tools() { return provider.tools(); }

    McpToolProvider provider() { return provider; }

    List<McpInitResult> results() { return results; }

    /// Rebuilds the namespaced tool list from every registered connection and publishes a **new**
    /// immutable snapshot; the previous one is never mutated.
    private void republish(boolean strict) {
        if (closed.get()) {
            provider.publish(List.of());
            return;
        }
        var adapters = new ArrayList<McpToolAdapter>();
        connections.values().forEach(c -> c.tools().forEach(t -> adapters.add(new McpToolAdapter(c, t))));
        provider.publish(McpToolProvider.catalogOf(adapters, strict));
    }

    /// Resilient disconnect, in reverse connect order: every client closed in its own try/catch, a
    /// tally logged, and the tool list emptied so no stale tool survives the teardown.
    void closeAll() {
        if (!closed.compareAndSet(false, true)) return;
        var names = new ArrayList<>(connections.sequencedKeySet());
        int done = 0;
        for (int i = names.size() - 1; i >= 0; i--) {
            String server = names.get(i);
            try {
                connections.get(server).close();
                done++;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "closing MCP server " + server + " threw", e);
            }
        }
        provider.publish(List.of());
        LOG.log(System.Logger.Level.DEBUG, "closed {0} of {1} MCP connections", done, names.size());
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

    private static void joinQuietly(Fork fork) {
        try {
            fork.joinUntil(Instant.now().plus(CONNECT_BUDGET));
        } catch (TimeoutException _) {
            LOG.log(System.Logger.Level.WARNING, "MCP connect budget of {0}s expired", CONNECT_BUDGET.toSeconds());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<McpInitResult> collect(List<McpServerConfig> servers, LinkedHashMap<String, Attempt> attempts) {
        var out = new ArrayList<McpInitResult>(servers.size());
        for (McpServerConfig config : servers) {
            if (!config.enabled()) {
                out.add(McpInitResult.disabled(config.name()));
                continue;
            }
            String server = McpNaming.sanitizeServer(config.name());
            Attempt attempt = attempts.get(server);
            McpConnection connection = attempt.result();
            out.add(connection == null
                    ? McpInitResult.failed(config.name(), server, attempt.error())
                    : McpInitResult.ok(config.name(), server, connection.tools().size()));
        }
        return out;
    }

    /// One server's connect attempt. Resolving the handle is fault-isolated: a throw becomes a
    /// failed [McpInitResult], never a failure of the whole pool.
    private record Attempt(String server, Fork.Handle<McpConnection> handle) {

        McpConnection result() {
            try {
                return handle.get();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            } catch (RuntimeException | ExecutionException _) {
                return null;
            }
        }

        String error() {
            try {
                handle.get();
                return "";
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return "interrupted";
            } catch (ExecutionException e) {
                return describe(e.getCause());
            } catch (RuntimeException e) {
                return describe(e);
            }
        }

        private static String describe(Throwable t) {
            if (t == null) return "unknown failure";
            String message = t.getMessage();
            return message == null || message.isBlank()
                    ? t.getClass().getSimpleName()
                    : t.getClass().getSimpleName() + ": " + message;
        }
    }
}
