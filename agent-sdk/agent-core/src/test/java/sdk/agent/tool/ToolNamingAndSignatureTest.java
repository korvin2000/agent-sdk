package sdk.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sdk.agent.json.Json;

class ToolNamingAndSignatureTest {

    @Test
    void composesSanitisesAndCapsNames() {
        assertEquals("mcp__srv__search", ToolNaming.compose("mcp__srv", "search"));
        assertEquals("a_b_c", ToolNaming.compose("a.b c"));
        String longName = "x".repeat(80);
        String composed = ToolNaming.compose(longName);
        assertEquals(64, composed.length());
        assertTrue(composed.startsWith("x".repeat(57) + "_"));
        assertEquals(composed, ToolNaming.compose(longName));              // deterministic
        assertEquals("___", ToolNaming.compose("!!!"));
        assertThrows(IllegalArgumentException.class, () -> ToolNaming.compose(""));
        assertThrows(IllegalArgumentException.class, () -> ToolNaming.requireValid("has space"));
    }

    @Test
    void signatureIgnoresKeyOrderAndSeparatesNameFromArguments() {
        var a = ToolCallSignature.of("read", Json.parse("{\"path\":\"x\",\"limit\":2}"));
        var b = ToolCallSignature.of("read", Json.parse("{\"limit\":2,\"path\":\"x\"}"));
        assertEquals(a, b);
        assertEquals(12, a.value().length());
        assertNotEquals(ToolCallSignature.of("a", Json.str("b:c")), ToolCallSignature.of("a:b", Json.str("c")));
    }

    record P(String v) { }

    private static Tool<P> tool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "d"; }
            @Override public ParamCodec<P> params() { return ParamCodec.ofRecord(P.class); }
            @Override public ToolResult execute(ToolInvocation<P> call) { return ToolResult.text(call.params().v()); }
        };
    }

    @Test
    void registryIsOrderedFailClosedAndHashed() {
        var registry = ToolRegistry.of(List.of(ToolProvider.of("a", List.of(tool("read"), tool("bash"))), ToolProvider.of("b", List.of(tool("edit")))));
        assertEquals(List.of("read", "bash", "edit"), List.copyOf(registry.names()));
        assertEquals("b", registry.ownerOf("edit").orElseThrow());
        assertTrue(registry.resolve("nope").isEmpty());
        assertEquals(64, registry.hash().length());

        var collision = assertThrows(ToolNameCollisionException.class,
                () -> ToolRegistry.of(List.of(ToolProvider.of("a", List.of(tool("read"))), ToolProvider.of("b", List.of(tool("read"))))));
        assertEquals("a", collision.firstOwner());
        assertEquals("b", collision.secondOwner());

        var replaced = registry.with("b", ToolCatalog.of(List.of(tool("write"))));
        assertEquals(List.of("read", "bash", "write"), List.copyOf(replaced.names()));
        assertNotEquals(registry.hash(), replaced.hash());
        assertEquals(List.of("read", "bash", "edit"), List.copyOf(registry.names()));   // untouched
    }
}
