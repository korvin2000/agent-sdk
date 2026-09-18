package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;

/// Discovery and refresh races over a real child process and the SDK's stdio transport.
@Timeout(30)
class McpDiscoveryIntegrationTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void discoversAllPagesBeforePublishingTheCatalog() throws Exception {
        try (var fixture = fixture("TWO_PAGE", "two-page")) {
            McpInitResult result = fixture.extension().initResults().getFirst();
            assertTrue(result.connected(), () -> "connect failed: " + result.error().orElse("?"));
            assertEquals(List.of("mcp__two-page__first", "mcp__two-page__second"),
                    names(fixture.extension()));
        }
    }

    @Test
    void rejectsARepeatedCursorWithoutPublishingAPartialCatalog() throws Exception {
        try (var fixture = fixture("CYCLE", "cycle")) {
            McpInitResult result = fixture.extension().initResults().getFirst();
            assertTrue(!result.connected());
            assertTrue(result.error().orElseThrow().contains("repeated cursor"));
            assertTrue(fixture.extension().contributions().toolProviders().getFirst().tools().isEmpty());
        }
    }

    @Test
    void rejectsTheHundredPageBoundaryWithoutPublishingAPartialCatalog() throws Exception {
        try (var fixture = fixture("CAP", "cap")) {
            McpInitResult result = fixture.extension().initResults().getFirst();
            assertTrue(!result.connected());
            assertTrue(result.error().orElseThrow().contains("exceeded 100 pages"));
            assertTrue(fixture.extension().contributions().toolProviders().getFirst().tools().isEmpty());
        }
    }

    @Test
    void notificationsDuringRefreshConvergeToLatestCatalogWithOneRefreshAtATime() throws Exception {
        try (var fixture = fixture("REFRESH", "refresh")) {
            McpExtension extension = fixture.extension();
            assertTrue(extension.initResults().getFirst().connected());
            List<String> initial = names(extension);
            assertTrue(initial.contains("mcp__refresh__set"));

            call(extension, "mcp__refresh__set", Json.obj("generation", Json.num(1)));
            call(extension, "mcp__refresh__await", Json.Obj.EMPTY);
            assertEquals(initial, names(extension), "blocked refresh must not publish a partial catalog");

            call(extension, "mcp__refresh__set", Json.obj("generation", Json.num(2)));
            call(extension, "mcp__refresh__release", Json.Obj.EMPTY);
            ToolResult stats = call(extension, "mcp__refresh__stats", Json.Obj.EMPTY);
            assertEquals("{\"lists\":3,\"peak\":1}", stats.text());
            awaitName(extension, "mcp__refresh__latest_2");
            assertTrue(names(extension).contains("mcp__refresh__latest_2"));
        }
    }

    @Test
    void closeRacingBlockedRefreshCannotRepublishAndChildExitsNormally() throws Exception {
        try (var fixture = fixture("CLOSE", "close")) {
            McpExtension extension = fixture.extension();
            assertTrue(extension.initResults().getFirst().connected());
            call(extension, "mcp__close__set", Json.obj("generation", Json.num(1)));
            call(extension, "mcp__close__await", Json.Obj.EMPTY);
            var provider = extension.contributions().toolProviders().getFirst();

            extension.close();
            assertTrue(provider.tools().isEmpty());
            fixture.child().onExit().get(5, TimeUnit.SECONDS);
            assertTrue(provider.tools().isEmpty());
        }
    }

    private static Fixture fixture(String scenario, String name) throws Exception {
        Path marker = Files.createTempFile("mcp-" + name + "-", ".pid");
        Files.deleteIfExists(marker);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> args = List.of("-cp", System.getProperty("java.class.path"),
                PaginatedMcpServerMain.class.getName(), scenario, marker.toString());
        McpServerConfig config = new McpServerConfig.Stdio(name, java.toString(), args, Map.of(),
                marker.getParent(), REQUEST_TIMEOUT, Set.of(), true);
        var extension = new McpExtension(List.of(config));
        var child = ProcessHandle.of(Long.parseLong(Files.readString(marker))).orElse(null);
        return new Fixture(extension, child, marker);
    }

    private static List<String> names(McpExtension extension) {
        return extension.contributions().toolProviders().getFirst().tools().stream().map(Tool::name).toList();
    }

    @SuppressWarnings("unchecked")
    private static ToolResult call(McpExtension extension, String name, Json arguments) throws Exception {
        Tool<Json> tool = (Tool<Json>) extension.contributions().toolProviders().getFirst().tools().stream()
                .filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
        return tool.execute(new ToolInvocation<>("call-" + name, name, arguments, arguments,
                Cancellation.create(), null));
    }
    private static void awaitName(McpExtension extension, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !names(extension).contains(expected)) {
            Thread.sleep(10);
        }
        assertTrue(names(extension).contains(expected), "catalog did not converge to " + expected);
    }


    private record Fixture(McpExtension extension, ProcessHandle child, Path pidFile) implements AutoCloseable {
        @Override public void close() throws java.io.IOException {
            extension.close();
            try {
                if (child != null) child.onExit().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting fixture shutdown", e);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new AssertionError("stdio fixture did not exit", e);
            } finally {
                Files.deleteIfExists(pidFile);
            }
        }
    }
}
