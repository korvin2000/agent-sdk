package sdk.agent.mcp;

import java.util.List;
import java.util.Objects;

import sdk.agent.spi.Contributions;
import sdk.agent.spi.Extension;

/// The entry point, and the whole of the coupling between this module and the core. Drop the
/// module from the classpath and the host loses one `.extension(...)` line: no core type, no
/// prompt section and no tool interface changes shape. Note what is *not* contributed — no hook,
/// no message codec, no prompt contributor: remote tools describe themselves through
/// `Tool.description()` like every other tool, so `## AVAILABLE TOOLS` picks them up with no
/// MCP-specific rendering.
///
/// ## Verified against mcp-core 2.0.1 — the eleven flagged facts
///
/// Every answer below was read out of the resolved artifacts
/// (`io.modelcontextprotocol.sdk:mcp-core:2.0.1`, `mcp-json-jackson2:2.0.1`), not from
/// documentation.
///
/// 1. **Does `StdioClientTransport` drain the child's stderr? YES — no wrapper needed.**
///    `connect()` calls `startErrorProcessing()`, which schedules a loop on a dedicated
///    single-thread `errorScheduler` reading `Process.getErrorStream()` through a `BufferedReader`
///    until EOF and emitting each line into `errorSink`. `setStdErrorHandler(Consumer<String>)`
///    and `getErrorSink()` are public. The pipe cannot fill and block the child.
/// 2. **`McpSchema.Content` is NOT sealed**, so the `default` arm in [McpContentMapper] is
///    present, exactly as §4.14.4 prints it. Declared `public interface McpSchema$Content extends
///    McpSchema$Meta` with a `default String type()` and no `permits`. Implementations found:
///    `TextContent`, `ImageContent`, `AudioContent`, `EmbeddedResource`, `ResourceLink` — the
///    spec's case list is complete for this release. `EmbeddedResource.resource()` is an
///    `McpSchema.ResourceContents`, also unsealed, with `TextResourceContents.text()` and
///    `BlobResourceContents.blob()`; there is no `textOf`/`blobOf`.
/// 3. **`CallToolResult.structuredContent()` EXISTS**, returning `java.lang.Object` (not a `Map`),
///    alongside `content()`, `isError()` and `meta()`. It is plumbed into `ToolResult.details()`
///    through [McpJsonBridge].
/// 4. **`ServerParameters` has NO working directory.** Its API is `builder(String command)`,
///    `.args(List|String...)`, `.arg`, `.env(Map)`, `.addEnvVar`, `.build()`, with getters
///    `getCommand/getArgs/getEnv`. `StdioClientTransport(ServerParameters, McpJsonMapper)` is the
///    two-arg constructor the spec assumed (a third `int inputMaxSize` overload also exists). cwd
///    is supplied by overriding the **`protected ProcessBuilder getProcessBuilder()`**: `connect()`
///    calls only `command(...)`, `environment().putAll(...)` and `start()` on the returned builder
///    and never `directory(...)`, so the override survives. See
///    `McpTransports.CwdStdioClientTransport`. `.env(map)` is additive over a filtered
///    `System.getenv()`, not a replacement.
/// 5. **HTTP header injection exists, but NOT as `customizeRequest`.** The real API is
///    `HttpClientStreamableHttpTransport.builder(baseUri).httpRequestCustomizer(
///    McpSyncHttpClientRequestCustomizer)`, whose single method is
///    `customize(HttpRequest.Builder, String method, URI endpoint, String body,
///    McpTransportContext)`. An async variant takes `McpAsyncHttpClientRequestCustomizer`. The
///    builder also splits base URI from `endpoint(String)`, defaulting to `/mcp` and joining them
///    with `Utils.resolveUri`, which is why [McpTransports] splits the configured `url`.
/// 6. **`McpSyncClient` interrupt semantics: the in-flight request IS abandoned client-side.**
///    `callTool` is `delegate.callTool(req)` … `.block()` — no timeout argument. Reactor 3.7.0's
///    `BlockingSingleSubscriber.blockingGet()` catches `InterruptedException`, calls `dispose()`
///    (cancelling the subscription), re-sets the thread's interrupt flag and rethrows via
///    `Exceptions.propagate`. The **server** is not told (see item 8), so the SDK-side
///    `requestTimeout` is still what terminates it.
/// 7. **`McpSyncClient implements AutoCloseable`**, with `public void close()` (delegates to
///    `McpAsyncClient.close()`, no wait) **and** `public boolean closeGracefully()` (blocks on
///    `Mono.block(Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS))`, logs "Client didn't close within
///    timeout" and returns the outcome). [McpConnection] tries graceful first, then hard.
/// 8. **No per-request cancellation.** `notifications/cancelled` does not appear anywhere in the
///    jar. The complete notification set is `notifications/{initialized, message, progress,
///    prompts/list_changed, resources/list_changed, resources/updated, roots/list_changed,
///    tools/list_changed, elicitation/complete}`. The design is `requestTimeout` + interrupt, as
///    assumed. `tools/list_changed` **is** supported and is surfaced as
///    `McpClient.SyncSpec.toolsChangeConsumer(Consumer<List<McpSchema.Tool>>)`, which is what
///    drives [McpToolProvider#catalogUpdates].
/// 9. **Gradle 9.7.1 resolves and compiles this module on the JDK 26 toolchain** — the same
///    `sdk.java-conventions` toolchain, `--release 26` and `-Werror` as every other module, with
///    mcp-core's Java 17 baseline causing no conflict.
/// 10. **Transitive versions, resolved from the real graph** (`:agent-mcp:dependencies`):
///     `compileClasspath` — `org.slf4j:slf4j-api:2.0.16`,
///     `com.fasterxml.jackson.core:jackson-annotations:2.21`,
///     `io.projectreactor:reactor-core:3.7.0` → `org.reactivestreams:reactive-streams:1.0.4`.
///     `runtimeClasspath` adds, via `mcp-json-jackson2`, `jackson-databind:2.21.1`,
///     `jackson-core:2.21.1`, `com.networknt:json-schema-validator:2.0.4`,
///     `jackson-dataformat-yaml:2.21.1`, `org.yaml:snakeyaml:2.5`, `com.ethlo.time:itu:1.14.0`,
///     and bumps slf4j to `2.0.17`. **Reactor is compile-scope and unavoidable** — the whole
///     argument for the module split. **`jakarta.servlet-api` is NOT a dependency at all**: the
///     OSGi manifest imports `jakarta.servlet*` as `resolution:=optional` and Maven pulls nothing,
///     so the spec's "provided" note overstates it. `mcp-json-jackson2` registers
///     `io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier` through
///     `META-INF/services/io.modelcontextprotocol.json.McpJsonMapperSupplier`, so `runtimeOnly`
///     genuinely suffices and no MCP JSON type appears in our compiled signatures.
/// 11. **The upstream JPMS issue was not verified** and nothing here depends on it. What *is*
///     confirmed is the shape that motivated the decision: `McpJsonDefaults.getMapper()` resolves
///     its mapper through `McpServiceLoader<McpJsonMapperSupplier, McpJsonMapper>`, and mcp-core
///     ships only `Automatic-Module-Name: io.modelcontextprotocol.sdk.mcp-core` — an automatic
///     module itself. A named module here would `requires` a chain we do not control, so this
///     module ships `Automatic-Module-Name: sdk.agent.mcp` and no `module-info.java`.
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
