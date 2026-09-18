package sdk.agent.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// How one MCP server is reached. **The transport is the type**, so the factory `switch` in
/// [McpTransports] needs no `default` and no `never` trick.
///
/// Dropped from the shape this was ported from: `alwaysAllow` (permissions are out of scope) and
/// `websocket` (the Java SDK ships no WebSocket client transport, and two sealed cases beat three).
/// **Added: [Stdio#cwd]**, which every real stdio server needs.
public sealed interface McpServerConfig {

    /// Used when a config leaves `requestTimeout` null. The SDK's own timeout is set slightly
    /// longer than this so ours wins and produces a uniform error envelope.
    Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /// Raw, as written by the host. [McpNaming#sanitizeServer] is applied once, at pool start.
    String name();

    Duration requestTimeout();

    /// Empty means every tool the server advertises. Matched against the **remote** name.
    Set<String> toolFilter();

    boolean enabled();

    /// A child process speaking JSON-RPC over stdin/stdout.
    ///
    /// @param cwd the child's working directory, or `null` to inherit ours. `ServerParameters`
    ///            carries no working directory, so [McpTransports] supplies one
    ///            by overriding `StdioClientTransport.getProcessBuilder()`.
    record Stdio(String name, String command, List<String> args, Map<String, String> env,
                 Path cwd, Duration requestTimeout, Set<String> toolFilter, boolean enabled)
            implements McpServerConfig {

        public Stdio {
            name = requireText(name, "name");
            command = requireText(command, "command");
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
            requestTimeout = timeoutOr(requestTimeout);
            toolFilter = filterOf(toolFilter);
        }

        /// Enabled, no extra environment, inherited working directory, default timeout, no filter.
        public static Stdio of(String name, String command, String... args) {
            return new Stdio(name, command, List.of(args), Map.of(), null, null, Set.of(), true);
        }
    }

    /// A Streamable HTTP endpoint. `url` is split into the transport's base URI and endpoint path
    /// by [McpTransports]; a URL with no path uses the SDK default `/mcp`.
    record Http(String name, URI url, Map<String, String> headers,
                Duration requestTimeout, Set<String> toolFilter, boolean enabled)
            implements McpServerConfig {

        public Http {
            name = requireText(name, "name");
            Objects.requireNonNull(url, "url");
            String scheme = url.getScheme();
            if ((scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
                    || url.getHost() == null || url.getHost().isBlank()
                    || url.getUserInfo() != null || url.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "MCP http url must use http/https with a host and no user-info or fragment: " + url);
            }
            headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            requestTimeout = timeoutOr(requestTimeout);
            toolFilter = filterOf(toolFilter);
        }

        public static Http of(String name, URI url) {
            return new Http(name, url, Map.of(), null, Set.of(), true);
        }
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MCP server " + what + " must not be blank");
        return value;
    }

    private static Duration timeoutOr(Duration requested) {
        if (requested == null) return DEFAULT_REQUEST_TIMEOUT;
        if (requested.isNegative() || requested.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive, was " + requested);
        }
        return requested;
    }

    private static Set<String> filterOf(Set<String> requested) {
        return requested == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(requested));
    }
}
