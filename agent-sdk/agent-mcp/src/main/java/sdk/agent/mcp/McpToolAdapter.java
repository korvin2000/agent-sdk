package sdk.agent.mcp;

import java.util.List;
import java.util.Objects;

import io.modelcontextprotocol.spec.McpSchema;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;

/// One remote tool, wearing the core [Tool] contract. It also **is** the authoritative
/// `agentName → (server, remoteName)` mapping: both halves are fields, so no code anywhere has to
/// reverse-parse a composed name.
final class McpToolAdapter implements Tool<Json> {

    private final McpCaller connection;
    private final String remoteName;
    private final String name;
    private final String description;
    private final ParamCodec<Json> params;
    private final ToolKind kind;

    McpToolAdapter(McpCaller connection, McpSchema.Tool remote) {
        this.connection = Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(remote, "remote");
        this.remoteName = Objects.requireNonNull(remote.name(), "remote tool name");
        this.name = McpNaming.compose(connection.server(), remoteName);
        this.description = describe(connection.server(), remote);
        // An MCP inputSchema is OPAQUE DATA. Stored exactly as received, handed to the provider
        // untouched, never re-derived, never normalised, never "fixed". Reconstructing it as
        // {type, properties, required} destroys $defs, $ref, oneOf/anyOf, additionalProperties,
        // root enum, default and every nested description.
        this.params = ParamCodec.passthrough(McpJsonBridge.toJsonObj(remote.inputSchema()));
        this.kind = kindOf(remote);
    }

    /// The server half of the authoritative mapping.
    String server() { return connection.server(); }

    /// The remote half of the authoritative mapping.
    String remoteName() { return remoteName; }

    @Override public String name() { return name; }

    @Override public String description() { return description; }

    @Override public ParamCodec<Json> params() { return params; }

    @Override public ToolKind kind() { return kind; }

    @Override public ToolResult execute(ToolInvocation<Json> call) throws InterruptedException {
        if (!(call.params() instanceof Json.Obj)) {
            return new ToolResult.Err(ErrorKind.INVALID_ARGUMENTS,
                    List.of(ContentBlock.Text.of("MCP tool arguments must be a JSON object")), Json.Null.NULL);
        }
        return connection.call(remoteName, call.params(), call.cancel());
    }

    /// `annotations.readOnlyHint` is supplied by the very server being classified, so it is
    /// advisory: absence, `false`, or no annotations at all all mean [ToolKind#MUTATING], the
    /// fail-safe answer. Only an explicit `true` moves a tool into the parallel-eligible class.
    private static ToolKind kindOf(McpSchema.Tool remote) {
        McpSchema.ToolAnnotations a = remote.annotations();
        return a != null && Boolean.TRUE.equals(a.readOnlyHint()) ? ToolKind.READ_ONLY : ToolKind.MUTATING;
    }

    /// The `[MCP:<server>]` prefix is prompt, not documentation: it is what tells the model that
    /// this tool lives behind a remote server and which one.
    private static String describe(String server, McpSchema.Tool remote) {
        String text = remote.description();
        if (text == null || text.isBlank()) text = remote.title();
        if (text == null || text.isBlank()) text = remote.name();
        return "[MCP:%s] %s".formatted(server, text);
    }
}
