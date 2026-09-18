package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;

/// Real Streamable HTTP MCP traffic through the SDK transport and the adapter, against a
/// deliberately small stateless JSON-RPC endpoint on the loopback interface: no network, no
/// credentials, no framework. Pins that the configured path, query and headers reach the wire.
@Timeout(30)
class McpHttpIntegrationTest {

    @Test
    void usesTheConfiguredPathQueryAndHeadersAndPreservesStructuredOutput() throws Exception {
        try (var fixture = new HttpFixture()) {
            URI endpoint = URI.create("http://127.0.0.1:" + fixture.port() + "/custom/mcp?tenant=acme");
            var config = new McpServerConfig.Http("loopback", endpoint,
                    Map.of("Authorization", "Bearer test-token"), Duration.ofSeconds(5), Set.of(), true);
            var extension = new McpExtension(List.of(config));
            try {
                var init = extension.initResults().getFirst();
                assertTrue(init.connected(), () -> "MCP HTTP connect failed: " + init.error().orElse("?"));
                @SuppressWarnings("unchecked")
                Tool<Json> tool = (Tool<Json>) extension.contributions().toolProviders().getFirst().tools().getFirst();

                ToolResult result = tool.execute(new ToolInvocation<>("call-1", tool.name(),
                        Json.obj("value", Json.num(7)), Json.obj("value", Json.num(7)), Cancellation.create(), null));

                assertInstanceOf(ToolResult.Ok.class, result);
                assertEquals(List.of(new ContentBlock.Text("summary", null), new ContentBlock.Text("{\"answer\":42}", null)), result.content());
                assertEquals(Json.obj("answer", Json.num(42)), result.details());
                assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"), fixture.methods());
                fixture.requests().forEach(request -> {
                    assertEquals("/custom/mcp", request.path());
                    assertEquals("tenant=acme", request.query());
                    assertEquals("Bearer test-token", request.authorization());
                });
            } finally {
                extension.close();
            }
        }
    }

    private record Request(String path, String query, String authorization, String method) { }

    private static final class HttpFixture implements AutoCloseable {
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        HttpFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/custom/mcp", this::handle);
            server.start();
        }

        int port() { return server.getAddress().getPort(); }

        List<Request> requests() { return List.copyOf(requests); }

        List<String> methods() { return requests.stream().map(Request::method).toList(); }

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Json.Obj request = body.isBlank() ? Json.Obj.EMPTY : (Json.Obj) Json.parse(body);
            String method = ((Json.Str) request.get("method").orElseThrow()).value();
            requests.add(new Request(uri.getPath(), uri.getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"), method));

            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String id = request.get("id").orElseThrow().toText();
            String response = switch (method) {
                case "initialize" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                        + "\"serverInfo\":{\"name\":\"loopback\",\"version\":\"1\"}}}";
                case "tools/list" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"tools\":[{\"name\":\"answer\",\"description\":\"returns an answer\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}}";
                case "tools/call" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"summary\"}],"
                        + "\"structuredContent\":{\"answer\":42},\"isError\":false}}";
                default -> throw new IllegalArgumentException("unexpected MCP method: " + method);
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        @Override public void close() { server.stop(0); }
    }
}
