package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;

/// End to end over the real protocol: a child JVM running [EchoMcpServerMain] as an MCP stdio
/// server, reached through the real `StdioClientTransport`, `McpSyncClient`, [McpConnectionPool]
/// and [McpToolAdapter].
///
/// mcp-core 2.0.1 ships no in-process or loopback client transport, so this is the only way to
/// exercise the wire without a Servlet container — see [EchoMcpServerMain].
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class McpStdioIntegrationTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private Path serverCwd;
    private McpExtension extension;
    private Map<String, Tool<?>> tools;

    @BeforeAll
    void connect() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> args = List.of("-cp", System.getProperty("java.class.path"),
                EchoMcpServerMain.class.getName());
        // A real, non-inherited working directory: proves the getProcessBuilder() override, which
        // is how cwd is supplied at all since ServerParameters carries none.
        serverCwd = Files.createTempDirectory("mcp-server-cwd").toRealPath();

        extension = new McpExtension(List.<McpServerConfig>of(new McpServerConfig.Stdio(
                "echo srv", java.toString(), args, Map.of(), serverCwd, REQUEST_TIMEOUT, Set.of(), true)));

        McpInitResult result = extension.initResults().getFirst();
        assertTrue(result.connected(), () -> "connect failed: " + result.error().orElse("?"));
        tools = extension.contributions().toolProviders().getFirst().catalog().tools();
    }

    @AfterAll
    void disconnect() throws Exception {
        if (extension != null) extension.close();
        if (serverCwd != null) Files.deleteIfExists(serverCwd);
    }

    @Test
    void discoversAndNamespacesEveryToolTheServerAdvertises() {
        assertEquals(List.of("mcp__echo_srv__echo", "mcp__echo_srv__boom", "mcp__echo_srv__cwd"),
                new ArrayList<>(tools.keySet()));
        assertEquals("[MCP:echo_srv] Returns its argument, after an optional delay.",
                tools.get("mcp__echo_srv__echo").description());
    }

    @Test
    void theInputSchemaSurvivesTheRoundTripUntouched() {
        Json.Obj schema = tools.get("mcp__echo_srv__echo").params().schema();

        // A {type, properties, required} reconstruction would have destroyed all of these.
        assertTrue(schema.has("$defs"), schema::toText);
        assertEquals(Json.str("#/$defs/Text"),
                ((Json.Obj) ((Json.Obj) schema.get("properties").orElseThrow())
                        .get("text").orElseThrow()).get("$ref").orElseThrow());
        assertEquals(Json.bool(false), schema.get("additionalProperties").orElseThrow());
        assertEquals(Json.str("the text to echo back"),
                ((Json.Obj) ((Json.Obj) schema.get("$defs").orElseThrow())
                        .get("Text").orElseThrow()).get("description").orElseThrow());
    }

    @Test
    void readOnlyHintTightensOnlyTheToolThatDeclaresIt() {
        assertEquals(ToolKind.READ_ONLY, tools.get("mcp__echo_srv__echo").kind());
        assertEquals(ToolKind.MUTATING, tools.get("mcp__echo_srv__boom").kind());
    }

    @Test
    void callsTheRemoteToolAndMapsEveryBlockPlusStructuredContent() throws Exception {
        ToolResult result = call("mcp__echo_srv__echo", Json.obj("text", Json.str("ping")));

        assertInstanceOf(ToolResult.Ok.class, result);
        assertEquals(2, result.content().size());
        assertEquals(new ContentBlock.Text("ping", null), result.content().get(0));
        assertEquals(new ContentBlock.Image("QUJD", "image/png"), result.content().get(1));
        assertEquals(Json.obj("length", Json.num(4)), result.details());
    }

    @Test
    void theChildRunsInTheConfiguredWorkingDirectory() throws Exception {
        ToolResult result = call("mcp__echo_srv__cwd", Json.Obj.EMPTY);

        assertEquals(serverCwd.toString(), result.text());
    }

    /// The second of MCP's two error channels: the tool ran to completion and reported failure as
    /// data. Never conflated with a transport fault.
    @Test
    void aServerReportedErrorBecomesToolReported() throws Exception {
        ToolResult result = call("mcp__echo_srv__boom", Json.Obj.EMPTY);

        assertEquals(ErrorKind.TOOL_REPORTED, assertInstanceOf(ToolResult.Err.class, result).kind());
        assertEquals("the tool ran and failed", result.text());
    }

    /// Our timeout wins because the SDK's is set longer, and it is not a transport fault: the
    /// connection stays usable afterwards even though the abandoned request is still running.
    @Test
    void aSlowCallTimesOutAndLeavesTheConnectionUsable() throws Exception {
        ToolResult timedOut = call("mcp__echo_srv__echo",
                Json.obj("text", Json.str("slow"), "delayMs", Json.num(6_000)));

        assertEquals(ErrorKind.TIMED_OUT, assertInstanceOf(ToolResult.Err.class, timedOut).kind());
        assertTrue(timedOut.text().contains("timed out"), timedOut::text);

        ToolResult after = call("mcp__echo_srv__echo", Json.obj("text", Json.str("still here")));
        assertInstanceOf(ToolResult.Ok.class, after);
        assertEquals("still here", ContentBlock.textOf(after.content()));
    }

    @SuppressWarnings("unchecked")
    private ToolResult call(String name, Json arguments) throws Exception {
        var tool = (Tool<Json>) tools.get(name);
        return tool.execute(new ToolInvocation<>("call-" + name, name, arguments, arguments,
                Cancellation.create(), null));
    }
}
