package sdk.agent.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;

/// The transport factory. Because [McpServerConfig] is sealed and the transport **is** the type,
/// this `switch` is exhaustive with no `default` and no `never` trick; unused bindings use `_`.
///
/// Config is validated before a transport is created, so a typo in a working directory fails
/// before a child process exists.
final class McpTransports {

    private McpTransports() { }

    static McpClientTransport create(McpServerConfig config) {
        return switch (config) {
            case McpServerConfig.Stdio(var _, var command, var args, var env, var cwd, var _, var _, var _) ->
                    stdio(command, args, env, cwd);

            case McpServerConfig.Http(var _, var url, var headers, var _, var _, var _) ->
                    http(url, headers);
        };
    }

    private static McpClientTransport stdio(String command, List<String> args, Map<String, String> env, Path cwd) {
        if (cwd != null && !Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("MCP stdio cwd is not a directory: " + cwd);
        }
        // ServerParameters.Builder seeds env from a filtered System.getenv() and `.env(map)` is
        // additive (verified), so this adds to the inherited environment rather than replacing it.
        ServerParameters params = ServerParameters.builder(command).args(args).env(env).build();
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        return cwd == null ? new StdioClientTransport(params, mapper)
                           : new CwdStdioClientTransport(params, mapper, cwd);
    }

    /// The transport builds its base URI and endpoint separately and joins them, defaulting the
    /// endpoint to `/mcp`. A host configures one `url`, so it is split here: origin → base URI,
    /// path (with query, if any) → endpoint.
    private static McpClientTransport http(URI url, Map<String, String> headers) {
        String base = url.getScheme() + "://" + url.getRawAuthority();
        String path = url.getRawPath();
        String endpoint = path == null || path.isEmpty() || "/".equals(path) ? "/mcp" : path;
        if (url.getRawQuery() != null) endpoint = endpoint + "?" + url.getRawQuery();

        var builder = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint);
        if (!headers.isEmpty()) {
            // HTTP MCP servers are almost always behind a bearer token. The customizer runs per
            // request, which is also what makes a rotating token work.
            builder = builder.httpRequestCustomizer(
                    (request, _, _, _, _) -> headers.forEach(request::header));
        }
        return builder.build();
    }

    /// `ServerParameters` carries no working directory (§4.14.10 item 4). `getProcessBuilder()` is
    /// `protected`, and `StdioClientTransport.connect()` only calls `command(...)`,
    /// `environment().putAll(...)` and `start()` on what it returns — it never touches
    /// `directory(...)` — so overriding it is the supported way to supply one. That beats the
    /// fallback of pre-resolving the command to an absolute path and passing `PWD`/`CD` in `env`,
    /// which does not actually change the child's working directory on either platform.
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
