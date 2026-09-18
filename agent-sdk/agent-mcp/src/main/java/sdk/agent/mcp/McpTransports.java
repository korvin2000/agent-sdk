package sdk.agent.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/// Transport construction and the narrow notification interception needed for bounded discovery.
final class McpTransports {

    private McpTransports() { }

    static McpClientTransport create(McpServerConfig config, Runnable toolsChanged) {
        Objects.requireNonNull(toolsChanged, "toolsChanged");
        McpClientTransport transport = switch (config) {
            case McpServerConfig.Stdio(var _, var command, var args, var env, var cwd, var _, var _, var _) ->
                    stdio(command, args, env, cwd);
            case McpServerConfig.Http(var _, var url, var headers, var _, var _, var _) ->
                    http(url, headers);
        };
        return new RefreshingTransport(transport, toolsChanged);
    }

    private static McpClientTransport stdio(String command, List<String> args, Map<String, String> env, Path cwd) {
        if (cwd != null && !Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("MCP stdio cwd is not a directory: " + cwd);
        }
        ServerParameters params = ServerParameters.builder(command).args(args).env(env).build();
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        return cwd == null ? new StdioClientTransport(params, mapper)
                           : new CwdStdioClientTransport(params, mapper, cwd);
    }

    private static McpClientTransport http(URI url, Map<String, String> headers) {
        String base = url.getScheme() + "://" + url.getRawAuthority();
        String path = url.getRawPath();
        String endpoint = path == null || path.isEmpty() || "/".equals(path) ? "/mcp" : path;
        if (url.getRawQuery() != null) endpoint += "?" + url.getRawQuery();

        var builder = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint);
        if (!headers.isEmpty()) {
            builder = builder.httpRequestCustomizer((request, _, _, _, _) -> headers.forEach(request::header));
        }
        return builder.build();
    }

    private static final class RefreshingTransport implements McpClientTransport {
        private final McpClientTransport delegate;
        private final Runnable toolsChanged;

        RefreshingTransport(McpClientTransport delegate, Runnable toolsChanged) {
            this.delegate = Objects.requireNonNull(delegate);
            this.toolsChanged = Objects.requireNonNull(toolsChanged);
        }

        @Override
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            return delegate.connect(messages -> messages.flatMap(message -> {
                if (message instanceof McpSchema.JSONRPCNotification notification
                        && McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED.equals(notification.method())) {
                    toolsChanged.run();
                    return Mono.empty();
                }
                return handler.apply(Mono.just(message));
            }));
        }

        @Override public void setExceptionHandler(Consumer<Throwable> handler) { delegate.setExceptionHandler(handler); }
        @Override public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }
        @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) { return delegate.sendMessage(message); }
        @Override public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) { return delegate.unmarshalFrom(data, typeRef); }
        @Override public List<String> protocolVersions() { return delegate.protocolVersions(); }
        @Override public void close() { delegate.close(); }
    }

    private static final class CwdStdioClientTransport extends StdioClientTransport {
        private final Path cwd;

        CwdStdioClientTransport(ServerParameters params, McpJsonMapper mapper, Path cwd) {
            super(params, mapper);
            this.cwd = cwd;
        }

        @Override protected ProcessBuilder getProcessBuilder() {
            return super.getProcessBuilder().directory(cwd.toFile());
        }
    }
}
