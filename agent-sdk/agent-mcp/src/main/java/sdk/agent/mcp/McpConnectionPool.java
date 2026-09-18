package sdk.agent.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import sdk.agent.concurrent.Fork;

/// Every connection this module owns. Connects in parallel, fault-isolated, and reports in
/// declaration order rather than completion order.
final class McpConnectionPool implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpConnectionPool.class.getName());
    private static final Duration CONNECT_BUDGET = Duration.ofSeconds(60);
    private static final Duration CONNECT_CLOSE_GRACE = Duration.ofSeconds(5);

    private final SequencedMap<String, McpConnection> connections;
    private final List<McpInitResult> results;
    private final McpToolProvider provider = new McpToolProvider();
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpConnectionPool(SequencedMap<String, McpConnection> connections, List<McpInitResult> results) {
        this.connections = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(connections));
        this.results = List.copyOf(results);
    }

    static McpConnectionPool connect(List<McpServerConfig> servers) {
        return connect(servers, CONNECT_BUDGET);
    }

    static McpConnectionPool connect(List<McpServerConfig> servers, Duration budget) {
        return connect(servers, budget, McpConnection::open);
    }

    static McpConnectionPool connect(List<McpServerConfig> servers, Duration budget,
            BiFunction<McpServerConfig, String, McpConnection> connector) {
        Objects.requireNonNull(servers, "servers");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("connect budget must be positive");
        SequencedMap<String, McpServerConfig> byServer = sanitizedNames(servers);
        var attempts = new LinkedHashMap<String, Attempt>();
        try (var fork = Fork.open(CONNECT_CLOSE_GRACE)) {
            byServer.forEach((server, config) -> {
                var attempt = new Attempt(server, config, connector);
                attempts.put(server, attempt);
                attempt.start(fork);
            });
            try {
                fork.joinUntil(Instant.now().plus(budget));
            } catch (TimeoutException e) {
                LOG.log(System.Logger.Level.WARNING, "MCP connect budget of {0}s expired", budget.toSeconds());
                attempts.values().forEach(Attempt::abandon);
            } catch (InterruptedException e) {
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
            Attempt.Resolved resolved = attempts.get(server).resolve();
            if (resolved.connection() != null) {
                live.put(server, resolved.connection());
                results.add(McpInitResult.ok(config.name(), server, resolved.connection().tools().size()));
            } else {
                results.add(McpInitResult.failed(config.name(), server, resolved.error()));
            }
        }

        var pool = new McpConnectionPool(live, results);
        try {
            pool.connections.values().forEach(c -> c.onToolsChanged(() -> pool.republish(false)));
            pool.republish(true);
            return pool;
        } catch (RuntimeException e) {
            try {
                pool.closeAll();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    McpToolProvider provider() { return provider; }
    List<McpInitResult> results() { return results; }

    private synchronized void republish(boolean strict) {
        if (closed.get()) return;
        var adapters = new ArrayList<McpToolAdapter>();
        connections.values().forEach(c -> c.tools().forEach(t -> adapters.add(new McpToolAdapter(c, t))));
        provider.publish(McpToolProvider.catalogOf(adapters, strict));
    }
    void closeAll() {
        List<McpConnection> owned;
        RuntimeException publishFailure = null;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            connections.values().forEach(c -> c.onToolsChanged(() -> { }));
            try {
                provider.publish(List.of());
            } catch (RuntimeException e) {
                publishFailure = e;
            }
            owned = new ArrayList<>(connections.reversed().values());
        }
        int done = 0;
        for (McpConnection connection : owned) {
            try {
                connection.close();
                done++;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "closing MCP connection threw", e);
            }
        }
        LOG.log(System.Logger.Level.DEBUG, "closed {0} of {1} MCP connections", done, owned.size());
        if (publishFailure != null) throw publishFailure;
    }
    @Override public void close() { closeAll(); }

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

    private static final class Attempt {
        private static final Object PENDING = new Object();
        private final String server;
        private final McpServerConfig config;
        private final BiFunction<McpServerConfig, String, McpConnection> connector;
        private final AtomicReference<Object> slot = new AtomicReference<>(PENDING);

        Attempt(String server, McpServerConfig config,
                BiFunction<McpServerConfig, String, McpConnection> connector) {
            this.server = server;
            this.config = config;
            this.connector = connector;
        }

        void start(Fork fork) {
            fork.fork("mcp-connect-" + server, () -> {
                try {
                    McpConnection candidate = connector.apply(config, server);
                    if (!slot.compareAndSet(PENDING, candidate)) candidate.close();
                } catch (RuntimeException e) {
                    slot.compareAndSet(PENDING, e);
                }
                return null;
            });
        }

        void abandon() { slot.compareAndSet(PENDING, Abandoned.INSTANCE); }

        Resolved resolve() {
            Object value = slot.get();
            if (value instanceof McpConnection connection) return new Resolved(connection, "");
            if (value instanceof Throwable t) return new Resolved(null, describe(t));
            return new Resolved(null, value == Abandoned.INSTANCE || value == PENDING
                    ? "connect budget expired" : "unknown failure");
        }

        private record Resolved(McpConnection connection, String error) { }

        private static String describe(Throwable t) {
            String message = t.getMessage();
            return message == null || message.isBlank()
                    ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
        }
    }
    private enum Abandoned { INSTANCE }
}
