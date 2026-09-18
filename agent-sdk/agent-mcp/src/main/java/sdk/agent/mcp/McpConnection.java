package sdk.agent.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import sdk.agent.concurrent.Cancellation;
import sdk.agent.concurrent.Fork;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolResult;

/// One live MCP server: the client, its tool list, and its health.
final class McpConnection implements McpCaller, AutoCloseable {

    enum Health { LIVE, DEGRADED, CLOSED }

    private static final System.Logger LOG = System.getLogger(McpConnection.class.getName());
    private static final String CLIENT_VERSION = "0.1.0";
    private static final Duration SDK_TIMEOUT_MARGIN = Duration.ofSeconds(5);
    private static final Duration CALL_CLOSE_GRACE = Duration.ofSeconds(1);
    private static final Duration BACKGROUND_CLOSE_GRACE = Duration.ofSeconds(2);
    private static final Duration BACKOFF_MIN = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(30);
    private static final int RECONNECT_ATTEMPTS = 6;
    private static final int MAX_LIST_PAGES = 100;

    private final McpServerConfig config;
    private final String server;
    private final McpContentMapper mapper;
    private final Fork background = Fork.open(BACKGROUND_CLOSE_GRACE);
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final AtomicBoolean refreshPending = new AtomicBoolean();

    private volatile McpSyncClient client;
    private volatile List<McpSchema.Tool> tools = List.of();
    private Thread refreshThread;
    private volatile Health health = Health.LIVE;
    private volatile Runnable onToolsChanged = () -> { };

    private McpConnection(McpServerConfig config, String server) {
        this.config = config;
        this.server = server;
        this.mapper = new McpContentMapper(server);
    }

    static McpConnection open(McpServerConfig config, String server) {
        var connection = new McpConnection(Objects.requireNonNull(config, "config"),
                                           Objects.requireNonNull(server, "server"));
        try {
            Connected connected = connection.connectOnce();
            synchronized (connection) {
                connection.client = connected.client();
                connection.tools = connected.tools();
            }
            connection.startRefreshIfNeeded();
            return connection;
        } catch (RuntimeException e) {
            connection.background.close();
            throw e;
        }
    }

    @Override public String server() { return server; }
    Health health() { return health; }
    List<McpSchema.Tool> tools() { return tools; }

    void onToolsChanged(Runnable listener) { onToolsChanged = Objects.requireNonNull(listener); }

    @Override
    public ToolResult call(String remoteName, Json arguments, Cancellation cancel) throws InterruptedException {
        Health snapshot = health;
        if (snapshot != Health.LIVE) {
            return ToolResult.error(ErrorKind.UNAVAILABLE,
                    "MCP server %s is %s; the call was not attempted.".formatted(server, snapshot));
        }
        McpSyncClient active = client;
        Duration timeout = config.requestTimeout();
        var request = McpSchema.CallToolRequest.builder(remoteName)
                .arguments(McpJsonBridge.toMap(arguments)).build();

        try (var fork = Fork.open(CALL_CLOSE_GRACE)) {
            var handle = fork.fork("mcp-" + server + "-" + remoteName, () -> active.callTool(request));
            try (var _ = cancel.onCancel(fork::cancelAll)) {
                fork.joinUntil(Instant.now().plus(timeout));
                return mapper.map(handle.get());
            } catch (TimeoutException _) {
                fork.cancelAll();
                return ToolResult.error(ErrorKind.TIMED_OUT,
                        "MCP tool %s timed out after %ds.".formatted(remoteName, timeout.toSeconds()));
            } catch (CancellationException _) {
                return ToolResult.error(ErrorKind.CANCELLED, "MCP tool %s was cancelled.".formatted(remoteName));
            } catch (ExecutionException e) {
                return failure(active, remoteName, e.getCause());
            }
        }
    }

    private ToolResult failure(McpSyncClient active, String remoteName, Throwable cause) {
        McpError rpc = jsonRpcErrorIn(cause);
        if (rpc != null) {
            return ToolResult.error(ErrorKind.TOOL_REPORTED,
                    "MCP tool %s failed: %s".formatted(remoteName, describe(rpc)));
        }
        markDegraded(active, cause);
        return ToolResult.error(ErrorKind.UNAVAILABLE,
                "MCP server %s is unreachable: %s".formatted(server, describe(cause)));
    }

    private static McpError jsonRpcErrorIn(Throwable t) {
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof McpError e) return e;
        }
        return null;
    }

    private static String describe(Throwable t) {
        if (t == null) return "unknown failure";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private void markDegraded(McpSyncClient expected, Throwable cause) {
        synchronized (this) {
            if (health != Health.LIVE || client != expected) return;
            health = Health.DEGRADED;
            if (refreshThread != null && refreshThread != Thread.currentThread()) refreshThread.interrupt();
        }
        LOG.log(System.Logger.Level.WARNING, "MCP server {0} degraded: {1}", server, describe(cause));
        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (health == Health.DEGRADED && reconnecting.compareAndSet(false, true)) background.run(this::reconnectLoop);
    }

    private void reconnectLoop() {
        try {
            Duration delay = BACKOFF_MIN;
            for (int attempt = 1; attempt <= RECONNECT_ATTEMPTS; attempt++) {
                if (health != Health.DEGRADED) return;
                Thread.sleep(delay);
                if (health != Health.DEGRADED) return;
                if (tryReconnect(attempt)) return;
                delay = delay.multipliedBy(2);
                if (delay.compareTo(BACKOFF_MAX) > 0) delay = BACKOFF_MAX;
            }
            LOG.log(System.Logger.Level.ERROR,
                    "MCP server {0} stayed unreachable after {1} attempts; it remains DEGRADED",
                    server, RECONNECT_ATTEMPTS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            reconnecting.set(false);
        }
    }

    private boolean tryReconnect(int attempt) {
        Connected connected;
        try {
            connected = connectOnce();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "MCP reconnect attempt {0} to {1} failed: {2}",
                    attempt, server, describe(e));
            return false;
        }
        McpSyncClient previous;
        boolean admitted;
        synchronized (this) {
            admitted = health != Health.CLOSED;
            previous = client;
            if (admitted) {
                client = connected.client();
                tools = connected.tools();
                health = Health.LIVE;
            }
        }
        if (!admitted) {
            closeQuietly(connected.client());
            return true;
        }
        closeQuietly(previous);
        LOG.log(System.Logger.Level.INFO, "MCP server {0} reconnected on attempt {1}", server, attempt);
        onToolsChanged.run();
        startRefreshIfNeeded();
        return true;
    }

    private Connected connectOnce() {
        McpClientTransport transport = McpTransports.create(config, this::requestRefresh);
        McpSyncClient candidate = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder(McpExtension.ID, CLIENT_VERSION).build())
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .requestTimeout(config.requestTimeout().plus(SDK_TIMEOUT_MARGIN))
                .initializationTimeout(config.requestTimeout().plus(SDK_TIMEOUT_MARGIN))
                .build();
        boolean handed = false;
        try {
            candidate.initialize();
            List<McpSchema.Tool> discovered = discover(candidate);
            handed = true;
            return new Connected(candidate, discovered);
        } finally {
            if (!handed) closeQuietly(candidate);
        }
    }

    private List<McpSchema.Tool> discover(McpSyncClient active) {
        try (var fork = Fork.open(Duration.ZERO)) {
            var handle = fork.fork("mcp-discover-" + server, () -> discoverPages(active));
            try {
                fork.joinUntil(Instant.now().plus(config.requestTimeout()));
                return handle.get();
            } catch (TimeoutException e) {
                fork.cancelAll();
                throw new IllegalStateException("MCP tools/list timed out after " + config.requestTimeout(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fork.cancelAll();
                throw new IllegalStateException("MCP tools/list interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("MCP tools/list failed", cause);
            }
        }
    }

    private List<McpSchema.Tool> discoverPages(McpSyncClient active) {
        if (active.getServerCapabilities() == null || active.getServerCapabilities().tools() == null) {
            LOG.log(System.Logger.Level.DEBUG, "MCP server {0} declares no tools capability", server);
            return List.of();
        }
        var all = new ArrayList<McpSchema.Tool>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            McpSchema.ListToolsResult result = active.listTools(cursor);
            if (result.tools() != null) all.addAll(result.tools());
            String next = result.nextCursor();
            if (next == null || next.isEmpty()) return filter(all);
            if (!seen.add(next)) throw new IllegalStateException("MCP server " + server + " tools/list repeated cursor: " + next);
            if (page == MAX_LIST_PAGES) {
                throw new IllegalStateException("MCP server " + server + " tools/list exceeded " + MAX_LIST_PAGES + " pages");
            }
            cursor = next;
        }
        throw new AssertionError("unreachable");
    }

    private List<McpSchema.Tool> filter(List<McpSchema.Tool> raw) {
        var keep = config.toolFilter();
        return raw.stream().filter(t -> t != null && t.name() != null)
                .filter(t -> keep.isEmpty() || keep.contains(t.name())).toList();
    }

    private void requestRefresh() {
        if (health == Health.CLOSED) return;
        refreshPending.set(true);
        startRefreshIfNeeded();
    }

    private synchronized void startRefreshIfNeeded() {
        if (client == null || health != Health.LIVE || !refreshPending.get()) return;
        if (refreshRunning.compareAndSet(false, true)) background.run(this::refreshLoop);
    }

    private void refreshLoop() {
        synchronized (this) {
            refreshThread = Thread.currentThread();
        }
        try {
            while (health == Health.LIVE && refreshPending.getAndSet(false)) {
                McpSyncClient active = client;
                if (active == null) break;
                try {
                    List<McpSchema.Tool> updated = discover(active);
                    synchronized (this) {
                        if (health != Health.LIVE || client != active) continue;
                        tools = updated;
                    }
                    onToolsChanged.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING,
                            "MCP server " + server + " tools refresh failed; retaining prior catalog", e);
                    markDegraded(active, e);
                }
            }
        } finally {
            synchronized (this) {
                refreshThread = null;
                refreshRunning.set(false);
            }
            if (refreshPending.get()) startRefreshIfNeeded();
        }
    }

    @Override public void close() {
        McpSyncClient closing;
        synchronized (this) {
            if (health == Health.CLOSED) return;
            health = Health.CLOSED;
            refreshPending.set(false);
            onToolsChanged = () -> { };
            closing = client;
            client = null;
            tools = List.of();
        }
        background.close();
        closeQuietly(closing);
    }

    private static void closeQuietly(McpSyncClient candidate) {
        if (candidate == null) return;
        try (var fork = Fork.open(Duration.ZERO)) {
            var handle = fork.fork("mcp-graceful-close", candidate::closeGracefully);
            try {
                fork.joinUntil(Instant.now().plusSeconds(1));
                if (Boolean.TRUE.equals(handle.get())) return;
                LOG.log(System.Logger.Level.DEBUG, "MCP graceful close reported incomplete");
            } catch (TimeoutException e) {
                handle.cancel();
                LOG.log(System.Logger.Level.WARNING, "MCP graceful close exceeded one second");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handle.cancel();
                LOG.log(System.Logger.Level.WARNING, "MCP graceful close interrupted");
            } catch (ExecutionException | CancellationException e) {
                LOG.log(System.Logger.Level.DEBUG, "MCP graceful client close failed", e);
            }
        }
        try {
            candidate.close();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "MCP client close threw", e);
        }
    }

    private record Connected(McpSyncClient client, List<McpSchema.Tool> tools) { }
}
