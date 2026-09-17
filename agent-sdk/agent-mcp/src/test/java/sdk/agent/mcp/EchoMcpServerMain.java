package sdk.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/// A real MCP server, run as a **child JVM** by [McpStdioIntegrationTest].
///
/// mcp-core 2.0.1 ships no in-process or loopback client transport: the only framework-agnostic
/// client transports are `StdioClientTransport` (which spawns a process) and
/// `HttpClientStreamableHttpTransport`, and the only framework-agnostic *server* transport is
/// `StdioServerTransportProvider` over `System.in`/`System.out` — the rest are Servlet-based and
/// `jakarta.servlet-api` is not even a dependency. So the honest way to exercise the real
/// protocol is to be a real stdio server, which has the side benefit of covering the child
/// process lifecycle, the `cwd` override and the stderr drain as well.
///
/// **Nothing but JSON-RPC may ever reach stdout here.** Diagnostics go to stderr, which the client
/// transport drains.
public final class EchoMcpServerMain {

    private EchoMcpServerMain() { }

    private static final Map<String, Object> ECHO_SCHEMA = Map.of(
            "type", "object",
            "$defs", Map.of("Text", Map.of("type", "string", "description", "the text to echo back")),
            "properties", Map.of("text", Map.of("$ref", "#/$defs/Text"),
                                 "delayMs", Map.of("type", "integer")),
            "required", List.of("text"),
            "additionalProperties", false);

    private static final Map<String, Object> EMPTY_SCHEMA =
            Map.of("type", "object", "properties", Map.of());

    public static void main(String[] args) throws InterruptedException {
        System.err.println("echo mcp server starting in " + System.getProperty("user.dir"));

        var echo = new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("echo", ECHO_SCHEMA)
                        .description("Returns its argument, after an optional delay.")
                        .annotations(new McpSchema.ToolAnnotations(null, true, null, null, null, null))
                        .build(),
                (_, request) -> {
                    Object delay = request.arguments().get("delayMs");
                    if (delay instanceof Number n && n.longValue() > 0) {
                        try {
                            Thread.sleep(n.longValue());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(String.valueOf(request.arguments().get("text")))
                            .addContent(McpSchema.ImageContent.builder("QUJD", "image/png").build())
                            .structuredContent(Map.of("length",
                                    String.valueOf(request.arguments().get("text")).length()))
                            .build();
                });

        var boom = new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("boom", EMPTY_SCHEMA)
                        .description("Always reports failure as data, the isError channel.")
                        .build(),
                (_, _) -> McpSchema.CallToolResult.builder()
                        .addTextContent("the tool ran and failed")
                        .isError(true)
                        .build());

        var cwd = new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("cwd", EMPTY_SCHEMA)
                        .description("Reports the server process working directory.")
                        .build(),
                (_, _) -> McpSchema.CallToolResult.builder()
                        .addTextContent(System.getProperty("user.dir"))
                        .build());

        McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("echo-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(echo, boom, cwd)
                .build();

        new CountDownLatch(1).await();     // the client destroys this process on close
    }
}
