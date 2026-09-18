package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class McpServerConfigTest {

    @Test
    void stdioDefaultsAreEnabledInheritedCwdAndNoFilter() {
        var cfg = McpServerConfig.Stdio.of("files", "npx", "-y", "@modelcontextprotocol/server-filesystem");

        assertEquals("files", cfg.name());
        assertEquals("npx", cfg.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem"), cfg.args());
        assertNull(cfg.cwd());
        assertEquals(Map.of(), cfg.env());
        assertEquals(McpServerConfig.DEFAULT_REQUEST_TIMEOUT, cfg.requestTimeout());
        assertEquals(Set.of(), cfg.toolFilter());
        assertTrue(cfg.enabled());
    }

    @Test
    void httpDefaultsMatchStdioDefaults() {
        var cfg = McpServerConfig.Http.of("api", URI.create("https://mcp.example.com/mcp"));

        assertEquals(Map.of(), cfg.headers());
        assertEquals(McpServerConfig.DEFAULT_REQUEST_TIMEOUT, cfg.requestTimeout());
        assertEquals(Set.of(), cfg.toolFilter());
        assertTrue(cfg.enabled());
    }

    @Test
    void nullTimeoutMeansTheDefaultAndANonPositiveOneIsRejected() {
        assertEquals(Duration.ofSeconds(5),
                new McpServerConfig.Stdio("s", "c", null, null, null, Duration.ofSeconds(5), null, true).requestTimeout());
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig.Stdio("s", "c", null, null, null, Duration.ZERO, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig.Stdio("s", "c", null, null, null, Duration.ofSeconds(-1), null, true));
    }

    @Test
    void collectionsAreDefensivelyCopiedAndOrderPreserving() {
        var args = new ArrayList<>(List.of("a", "b"));
        var env = new LinkedHashMap<>(Map.of("K", "V"));
        var filter = new LinkedHashSet<>(List.of("one", "two"));
        var cfg = new McpServerConfig.Stdio("s", "c", args, env, Path.of("."), null, filter, true);

        args.add("c");
        env.put("K2", "V2");
        filter.add("three");

        assertEquals(List.of("a", "b"), cfg.args());
        assertEquals(Map.of("K", "V"), cfg.env());
        assertEquals(List.of("one", "two"), new ArrayList<>(cfg.toolFilter()));
        assertThrows(UnsupportedOperationException.class, () -> cfg.env().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> cfg.toolFilter().add("x"));
    }

    @Test
    void aBlankNameOrCommandIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.Stdio.of("  ", "c"));
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.Stdio.of("s", " "));
        assertThrows(NullPointerException.class, () -> McpServerConfig.Http.of("s", null));
    }

    @Test
    void anHttpUrlMustBeAbsoluteWithAHost() {
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.Http.of("s", URI.create("/mcp")));
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.Http.of("s", URI.create("mcp")));
    }

    @Test
    void anHttpUrlRejectsOtherSchemesUserInfoAndFragments() {
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.Http.of("s", URI.create("ftp://host/mcp")));
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.Http.of("s", URI.create("https://user@host/mcp")));
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.Http.of("s", URI.create("https://host/mcp#fragment")));
    }

    /// The transport is the type, so a `switch` over the config needs no `default`.
    @Test
    void theSealedHierarchyIsExhaustiveWithoutADefaultArm() {
        List<McpServerConfig> configs = List.of(
                McpServerConfig.Stdio.of("a", "cmd"),
                McpServerConfig.Http.of("b", URI.create("https://h/mcp")));

        var kinds = configs.stream().map(c -> switch (c) {
            case McpServerConfig.Stdio _ -> "stdio";
            case McpServerConfig.Http _ -> "http";
        }).toList();

        assertEquals(List.of("stdio", "http"), kinds);
    }
}
