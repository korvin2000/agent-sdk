package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class McpConnectionPoolTest {

    /// Sanitisation happens ONCE, at pool start, and a collision is a hard error **before any
    /// connection is attempted** — no process is spawned, no socket opened.
    @Test
    void twoServerNamesThatSanitiseAlikeAreRejectedNamingBothOwners() {
        List<McpServerConfig> servers = List.of(
                McpServerConfig.Stdio.of("a.b", "cmd"),
                McpServerConfig.Stdio.of("a b", "cmd"));

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> McpConnectionPool.sanitizedNames(servers));

        assertTrue(thrown.getMessage().contains("a_b"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'a.b'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'a b'"), thrown.getMessage());
    }

    @Test
    void sanitisedNamesKeepDeclarationOrderAndSkipDisabledServers() {
        List<McpServerConfig> servers = List.of(
                McpServerConfig.Stdio.of("z.one", "cmd"),
                new McpServerConfig.Stdio("off", "cmd", null, null, null, null, null, false),
                McpServerConfig.Stdio.of("a two", "cmd"));

        var byServer = McpConnectionPool.sanitizedNames(servers);

        assertEquals(List.of("z_one", "a_two"), new ArrayList<>(byServer.sequencedKeySet()));
    }

    @Test
    void anEmptyServerListConnectsToNothingAndContributesAnEmptyCatalog() {
        try (var pool = McpConnectionPool.connect(List.of())) {
            assertEquals(List.of(), pool.results());
            assertTrue(pool.tools().isEmpty());
            assertEquals("sdk.agent.mcp", pool.provider().id());
        }
    }

    /// Fault isolation: a server whose command does not exist must not stop the pool, and must be
    /// reported in **declaration order** with the reason.
    @Test
    void aServerThatCannotStartIsReportedNotThrown() {
        List<McpServerConfig> servers = List.of(
                new McpServerConfig.Stdio("broken", "definitely-not-a-real-command-xyzzy",
                        null, null, null, Duration.ofSeconds(1), null, true),
                new McpServerConfig.Stdio("skipped", "cmd", null, null, null, null, null, false));

        try (var pool = McpConnectionPool.connect(servers)) {
            List<McpInitResult> results = pool.results();

            assertEquals(2, results.size());
            assertEquals("broken", results.get(0).name());
            assertTrue(results.get(0).error().isPresent());
            assertEquals(0, results.get(0).toolCount());

            assertEquals("skipped", results.get(1).name());
            assertEquals("disabled", results.get(1).error().orElseThrow());

            assertTrue(pool.tools().isEmpty());
        }
    }
}
