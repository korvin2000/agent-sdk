package sdk.agent.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
///
/// `McpSyncClient` is a blocking facade over a reactive core — `callTool` is
/// `McpAsyncClient.callTool(...).block()` with no timeout of its own. So every call goes through
/// [Fork], both because raw virtual-thread executors are banned outside `sdk.agent.concurrent` and
/// because `Fork.fork` is what rebinds `RunScope.CURRENT` into the call.
///
/// **Interrupt semantics, verified rather than assumed:** Reactor's `BlockingSingleSubscriber`
/// catches `InterruptedException`, calls `dispose()` (cancelling the subscription, so the client
/// stops waiting), restores the interrupt flag and rethrows wrapped. mcp-core 2.0.1 has no
/// `notifications/cancelled`, so the **server** keeps working on the abandoned request; the SDK's
/// own `requestTimeout` is set slightly longer than ours precisely so that request does terminate.
/// `Fork.close()` waits at most its grace period and then abandons — one virtual thread may leak,
/// by design, because a `close()` that waited for a non-interruptible task would hang the agent
/// inside our own code.
final class McpConnection implements McpCaller, AutoCloseable {

    /// `LIVE` — calls go out. `DEGRADED` — a transport fault was seen; calls fail fast with
    /// `UNAVAILABLE` rather than blocking, while a bounded-backoff reconnect runs. `CLOSED` —
    /// terminal.
    enum Health { LIVE, DEGRADED, CLOSED }

    private static final System.Logger LOG = System.getLogger(McpConnection.class.getName());
    private static final String CLIENT_VERSION = "0.1.0";

    /// Ours must win, so the SDK's is set this much longer; its only job is to terminate the
    /// request we have already abandoned.
    private static final Duration SDK_TIMEOUT_MARGIN = Duration.ofSeconds(5);
    private static final Duration CALL_CLOSE_GRACE = Duration.ofSeconds(1);
    private static final Duration BACKGROUND_CLOSE_GRACE = Duration.ofSeconds(2);
    private static final Duration BACKOFF_MIN = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(30);
    private static final int RECONNECT_ATTEMPTS = 6;
    /// `tools/list` is cursor-paginated; the bound stops a server that never clears its cursor.
    private static final int MAX_LIST_PAGES = 100;

    private final McpServerConfig config;
    private final String server;
    private final McpContentMapper mapper;
    private final Fork background = Fork.open(BACKGROUND_CLOSE_GRACE);
    private final AtomicBoolean reconnecting = new AtomicBoolean();

    private volatile McpSyncClient client;
    private volatile List<McpSchema.Tool> tools = List.of();
    private volatile Health health = Health.LIVE;
    private volatile Runnable onToolsChanged = () -> { };

    private McpConnection(McpServerConfig config, String server) {
        this.config = config;
        this.server = server;
        this.mapper = new McpContentMapper(server);
    }

    /// Validate → connect → initialize → list, or throw having left nothing running. The
    /// best-effort `close()` on the failure path is what stops a handshake-ok/list-failed stdio
    /// server from leaking its child process.
    static McpConnection open(McpServerConfig config, String server) {
        var connection = new McpConnection(Objects.requireNonNull(config, "config"),
                                           Objects.requireNonNull(server, "server"));
        try {
            connection.client = connection.connectOnce();
            return connection;
        } catch (RuntimeException e) {
            connection.background.close();
            throw e;
        }
    }

    @Override public String server() { return server; }

    Health health() { return health; }

    /// The remote tool list as of the last `tools/list` or `tools/list_changed`.
    List<McpSchema.Tool> tools() { return tools; }

    /// Set by the pool once this connection is registered, so a `list_changed` that arrives during
    /// the handshake cannot publish a catalog for a server that has not been admitted yet.
    void onToolsChanged(Runnable listener) { this.onToolsChanged = Objects.requireNonNull(listener); }

    // ---- calling ---------------------------------------------------------------------------

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
                .arguments(McpJsonBridge.toMap(arguments))
                .build();

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
                return failure(remoteName, e.getCause());
            }
        }
    }

    /// The two error channels stay separate. A JSON-RPC error response is the **server** answering
    /// — an unknown tool, invalid params — and says nothing about the transport, so it becomes
    /// `TOOL_REPORTED` and leaves health alone. Anything else is infrastructure: `UNAVAILABLE`
    /// **plus** a health transition, so the next call fails fast instead of blocking.
    private ToolResult failure(String remoteName, Throwable cause) {
        McpError rpc = jsonRpcErrorIn(cause);
        if (rpc != null) {
            return ToolResult.error(ErrorKind.TOOL_REPORTED,
                    "MCP tool %s failed: %s".formatted(remoteName, describe(rpc)));
        }
        markDegraded(cause);
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

    // ---- health ----------------------------------------------------------------------------

    private void markDegraded(Throwable cause) {
        synchronized (this) {
            if (health != Health.LIVE) return;
            health = Health.DEGRADED;
        }
        LOG.log(System.Logger.Level.WARNING, "MCP server {0} degraded: {1}", server, describe(cause));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return;
        background.run(this::reconnectLoop);
    }

    /// Bounded backoff, bounded attempts. A server that stays down leaves the connection
    /// `DEGRADED`, which the model reads as `UNAVAILABLE` on every call and can route around —
    /// rather than an endlessly retrying client, or a dead stdio server that throws forever.
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
        McpSyncClient replacement;
        try {
            replacement = connectOnce();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "MCP reconnect attempt {0} to {1} failed: {2}",
                    attempt, server, describe(e));
            return false;
        }
        McpSyncClient previous;
        synchronized (this) {
            if (health == Health.CLOSED) {
                closeQuietly(replacement);
                return true;
            }
            previous = client;
            client = replacement;
            health = Health.LIVE;
        }
        closeQuietly(previous);
        LOG.log(System.Logger.Level.INFO, "MCP server {0} reconnected on attempt {1}", server, attempt);
        onToolsChanged.run();
        return true;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    private McpSyncClient connectOnce() {
        McpClientTransport transport = McpTransports.create(config);
        McpSyncClient candidate = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder(McpExtension.ID, CLIENT_VERSION).build())
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .requestTimeout(config.requestTimeout().plus(SDK_TIMEOUT_MARGIN))
                // One knob, not two: a host that shortens its tool timeout also stops waiting
                // this long on a handshake that is never going to complete.
                .initializationTimeout(config.requestTimeout().plus(SDK_TIMEOUT_MARGIN))
                .toolsChangeConsumer(this::remoteToolsChanged)
                .build();
        boolean handed = false;
        try {
            candidate.initialize();
            this.tools = discover(candidate);
            handed = true;
            return candidate;
        } finally {
            if (!handed) closeQuietly(candidate);
        }
    }

    /// Capability-gated: a server that never declares `tools` is not asked for a list, which is
    /// one fewer doomed round trip per connect.
    private List<McpSchema.Tool> discover(McpSyncClient active) {
        McpSchema.ServerCapabilities caps = active.getServerCapabilities();
        if (caps == null || caps.tools() == null) {
            LOG.log(System.Logger.Level.DEBUG, "MCP server {0} declares no tools capability", server);
            return List.of();
        }
        var all = new ArrayList<McpSchema.Tool>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            McpSchema.ListToolsResult result = cursor == null ? active.listTools() : active.listTools(cursor);
            if (result.tools() != null) all.addAll(result.tools());
            cursor = result.nextCursor();
            if (cursor == null || cursor.isEmpty()) break;
        }
        return filter(all);
    }

    private List<McpSchema.Tool> filter(List<McpSchema.Tool> raw) {
        var keep = config.toolFilter();
        return raw.stream()
                .filter(t -> t != null && t.name() != null)
                .filter(t -> keep.isEmpty() || keep.contains(t.name()))
                .toList();
    }

    private void remoteToolsChanged(List<McpSchema.Tool> updated) {
        if (health == Health.CLOSED) return;
        this.tools = filter(updated == null ? List.of() : updated);
        LOG.log(System.Logger.Level.DEBUG, "MCP server {0} published {1} tools via tools/list_changed",
                server, tools.size());
        onToolsChanged.run();
    }

    @Override public void close() {
        synchronized (this) {
            if (health == Health.CLOSED) return;
            health = Health.CLOSED;
        }
        background.close();
        closeQuietly(client);
    }

    /// `McpSyncClient` is `AutoCloseable` with both `close()` (hands off to the async client, no
    /// wait) and `closeGracefully()` (blocks up to the SDK's own bounded timeout and reports
    /// whether it made it). Graceful first, hard on a `false` or a throw — teardown must not
    /// depend on a remote peer behaving.
    private static void closeQuietly(McpSyncClient candidate) {
        if (candidate == null) return;
        try {
            if (candidate.closeGracefully()) return;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "graceful MCP client close threw", e);
        }
        try {
            candidate.close();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "MCP client close threw", e);
        }
    }
}
