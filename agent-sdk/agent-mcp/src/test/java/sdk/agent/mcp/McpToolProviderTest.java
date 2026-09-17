package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;
import sdk.agent.tool.Tool;

class McpToolProviderTest {

    @Test
    void namespacesEveryToolAndKeepsDeclarationOrder() {
        var alpha = new StubCaller("alpha");
        var beta = new StubCaller("beta");

        List<Tool<?>> tools = McpToolProvider.catalogOf(List.of(
                new McpToolAdapter(alpha, remote("search")),
                new McpToolAdapter(alpha, remote("fetch")),
                new McpToolAdapter(beta, remote("search"))), true);

        // Two servers exposing `search` must NOT collapse: without namespacing the second would
        // overwrite the first and calls would route to the wrong server.
        assertEquals(List.of("mcp__alpha__search", "mcp__alpha__fetch", "mcp__beta__search"), names(tools));
    }

    @Test
    void aComposedNameCollisionIsAHardErrorAtRegistrationNamingBothOwners() {
        var srv = new StubCaller("srv");
        // Both remote names sanitise to `a_b`, so both compose to mcp__srv__a_b.
        List<McpToolAdapter> adapters = List.of(
                new McpToolAdapter(srv, remote("a b")),
                new McpToolAdapter(srv, remote("a.b")));

        var thrown = assertThrows(IllegalStateException.class, () -> McpToolProvider.catalogOf(adapters, true));

        assertTrue(thrown.getMessage().contains("mcp__srv__a_b"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("srv/a b"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("srv/a.b"), thrown.getMessage());
    }

    @Test
    void aCollisionArrivingLaterDropsTheDuplicateRatherThanMisroutingOrThrowing() {
        var srv = new StubCaller("srv");
        List<Tool<?>> tools = McpToolProvider.catalogOf(List.of(
                new McpToolAdapter(srv, remote("a b")),
                new McpToolAdapter(srv, remote("a.b")),
                new McpToolAdapter(srv, remote("other"))), false);

        assertEquals(List.of("mcp__srv__a_b", "mcp__srv__other"), names(tools));
        // the FIRST owner wins, so an existing tool never silently changes meaning
        assertEquals("a b", ((McpToolAdapter) tools.getFirst()).remoteName());
    }

    @Test
    void publishesANewListRatherThanMutatingTheOldOne() {
        var provider = new McpToolProvider();
        List<Tool<?>> first = McpToolProvider.catalogOf(List.of(new McpToolAdapter(new StubCaller("a"), remote("x"))), true);
        List<Tool<?>> second = McpToolProvider.catalogOf(List.of(
                new McpToolAdapter(new StubCaller("a"), remote("x")),
                new McpToolAdapter(new StubCaller("a"), remote("y"))), true);

        provider.publish(first);
        assertSame(first, provider.tools());
        provider.publish(second);
        assertSame(second, provider.tools());
        assertEquals(1, first.size());                                      // the old snapshot is untouched
    }

    @Test
    void identifiesItselfAsTheMcpExtension() {
        assertEquals("sdk.agent.mcp", new McpToolProvider().id());
    }

    private static McpSchema.Tool remote(String name) {
        return McpSchema.Tool.builder(name, Map.of()).description("d").build();
    }

    private static List<String> names(List<Tool<?>> tools) { return tools.stream().map(Tool::name).toList(); }
}
