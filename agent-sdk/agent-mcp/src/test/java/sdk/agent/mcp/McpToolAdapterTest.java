package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;
import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;

class McpToolAdapterTest {

    /// A schema that a `{type, properties, required}` reconstruction would destroy: `$defs`,
    /// `$ref`, `oneOf`, `additionalProperties`, a root `enum` and a nested `description`.
    private static final String HOSTILE_SCHEMA = """
            {"$defs":{"Point":{"type":"object","properties":{"x":{"type":"number",\
            "description":"the abscissa"}},"additionalProperties":false}},\
            "type":"object","properties":{"at":{"$ref":"#/$defs/Point"},\
            "mode":{"oneOf":[{"const":"fast"},{"const":"slow"}],"default":"fast"}},\
            "required":["at"],"additionalProperties":false}""";

    @Test
    void theInputSchemaIsPassedThroughByteForByte() {
        Json.Obj schema = (Json.Obj) Json.parse(HOSTILE_SCHEMA);
        var adapter = adapter("srv", remote("probe", schema, null));

        assertEquals(HOSTILE_SCHEMA, adapter.params().schema().toText());
        assertEquals(schema, adapter.params().schema());
    }

    @Test
    void passthroughBindsWithoutValidatingBecauseTheServerIsTheAuthority() throws Exception {
        var adapter = adapter("srv", remote("probe", (Json.Obj) Json.parse(HOSTILE_SCHEMA), null));
        Json arguments = Json.parse("""
                {"nothing":"the schema says is allowed"}""");

        assertSame(arguments, adapter.params().bind(arguments));
    }

    @Test
    void absentAnnotationsMeanMutating() {
        assertEquals(ToolKind.MUTATING, adapter("srv", remote("t", Json.Obj.EMPTY, null)).kind());
    }

    @Test
    void readOnlyHintMayOnlyTightenNeverRelax() {
        assertEquals(ToolKind.READ_ONLY, adapter("srv", remote("t", Json.Obj.EMPTY, Boolean.TRUE)).kind());
        // false and null are the same answer: the fail-safe one.
        assertEquals(ToolKind.MUTATING, adapter("srv", remote("t", Json.Obj.EMPTY, Boolean.FALSE)).kind());
        assertEquals(ToolKind.MUTATING, adapter("srv", remote("t", Json.Obj.EMPTY, null)).kind());
    }

    @Test
    void keepsBothHalvesOfTheMappingSoNothingHasToReverseParse() {
        var adapter = adapter("my_server", remote("list_files", Json.Obj.EMPTY, null));

        assertEquals("mcp__my_server__list_files", adapter.name());
        assertEquals("my_server", adapter.server());
        assertEquals("list_files", adapter.remoteName());
    }

    @Test
    void describesItselfAsRemoteAndFallsBackWhenTheServerSendsNoDescription() {
        assertEquals("[MCP:srv] read a file",
                adapter("srv", remote("read", Json.Obj.EMPTY, null, "read a file")).description());
        assertEquals("[MCP:srv] read", adapter("srv", remote("read", Json.Obj.EMPTY, null, null)).description());
    }

    @Test
    void executeForwardsTheRemoteNameAndArgumentsToTheConnection() throws Exception {
        var caller = new StubCaller("srv");
        var adapter = new McpToolAdapter(caller, remote("list_files", Json.Obj.EMPTY, null));
        Json arguments = Json.obj("path", Json.str("/tmp"));

        var result = adapter.execute(new ToolInvocation<>("call-1", adapter.name(), arguments,
                arguments, Cancellation.create(), null));

        assertEquals(List.of(new StubCaller.Call("list_files", arguments)), caller.calls);
        assertTrue(result.text().contains("stub"));
    }

    private static McpToolAdapter adapter(String server, McpSchema.Tool remote) {
        return new McpToolAdapter(new StubCaller(server), remote);
    }

    private static McpSchema.Tool remote(String name, Json.Obj schema, Boolean readOnlyHint) {
        return remote(name, schema, readOnlyHint, "d");
    }

    private static McpSchema.Tool remote(String name, Json.Obj schema, Boolean readOnlyHint, String description) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) McpJsonBridge.toSdk(schema);
        var annotations = readOnlyHint == null
                ? null
                : new McpSchema.ToolAnnotations(null, readOnlyHint, null, null, null, null);
        return McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .annotations(annotations)
                .build();
    }
}
