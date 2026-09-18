package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
    void connectBudgetMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> McpConnectionPool.connect(List.of(), Duration.ZERO));
    }

    @Test
    void anEmptyServerListConnectsToNothingAndContributesAnEmptyCatalog() {
        try (var pool = McpConnectionPool.connect(List.of())) {
            assertEquals(List.of(), pool.results());
            assertTrue(pool.provider().tools().isEmpty());
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

            assertTrue(pool.provider().tools().isEmpty());
        }
    }

    @Test
    void lateSuccessfulOpenAfterBudgetAbandonmentClosesItsRealStdioChild() throws Exception {
        var config = echoConfig("late-success");
        Set<Long> before = echoChildren();
        Set<Long> admitted = new HashSet<>();
        var opened = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var candidate = McpConnection.open(config, McpNaming.sanitizeServer(config.name()));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> McpConnectionPool.connect(List.of(config),
                    Duration.ofMillis(100), (requested, server) -> {
                        opened.countDown();
                        for (;;) {
                            try {
                                release.await();
                                return candidate;
                            } catch (InterruptedException _) {
                                // The pool has abandoned this attempt, but ownership is still held
                                // by this worker until it returns the candidate.
                                interrupted.countDown();
                            }
                        }
                    }));

            try {
                assertTrue(opened.await(15, TimeUnit.SECONDS), "real stdio child never connected");
                admitted.addAll(echoChildren());
                admitted.removeAll(before);
                assertEquals(1, admitted.size(), "expected one admitted child: " + admitted);
                assertTrue(interrupted.await(15, TimeUnit.SECONDS), "pool never abandoned the attempt");
                release.countDown();

                try (var pool = result.get(15, TimeUnit.SECONDS)) {
                    assertTrue(pool.results().getFirst().error().isPresent());
                    assertTrue(pool.provider().tools().isEmpty());
                }
            } finally {
                release.countDown();
            }
            assertTrue(awaitGone(admitted, Duration.ofSeconds(10)),
                    () -> "late candidate child still running: " + echoChildren());
        }
    }

    @Test
    void strictInitialCatalogCollisionClosesAllAdmittedRealStdioChildren() throws Exception {
        String first = "s".repeat(60) + "-60";
        String second = "s".repeat(60) + "-2936";
        assertEquals(McpNaming.compose(first, "echo"), McpNaming.compose(second, "echo"));
        List<McpServerConfig> servers = List.of(echoConfig(first), echoConfig(second));
        Set<Long> before = echoChildren();
        var allOpened = new CountDownLatch(2);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> McpConnectionPool.connect(servers, Duration.ofSeconds(15),
                    (requested, server) -> {
                        McpConnection candidate = McpConnection.open(requested, server);
                        allOpened.countDown();
                        awaitUninterruptibly(release);
                        return candidate;
                    }));

            try {
                assertTrue(allOpened.await(15, TimeUnit.SECONDS), "both real stdio children never connected");
                Set<Long> admitted = new HashSet<>(echoChildren());
                admitted.removeAll(before);
                assertEquals(2, admitted.size(), () -> "expected two admitted children, saw " + admitted);
                release.countDown();

                var thrown = assertThrows(ExecutionException.class, () -> result.get(15, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, thrown.getCause());
                assertTrue(awaitGone(admitted, Duration.ofSeconds(10)),
                        () -> "collision cleanup left children running: " + echoChildren());
            } finally {
                release.countDown();
            }
        }
    }

    private static McpServerConfig.Stdio echoConfig(String name) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new McpServerConfig.Stdio(name, java.toString(),
                List.of("-cp", System.getProperty("java.class.path"), EchoMcpServerMain.class.getName()),
                Map.of(), null, Duration.ofSeconds(3), Set.of(), true);
    }

    private static Set<Long> echoChildren() {
        return ProcessHandle.current().descendants()
                .filter(process -> process.info().commandLine()
                        .map(command -> command.contains(EchoMcpServerMain.class.getName())).orElse(false))
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static boolean awaitGone(Set<Long> pids, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (pids.stream().noneMatch(pid -> ProcessHandle.of(pid)
                    .map(ProcessHandle::isAlive).orElse(false))) return true;
            Thread.sleep(20);
        }
        return pids.stream().noneMatch(pid -> ProcessHandle.of(pid)
                .map(ProcessHandle::isAlive).orElse(false));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException _) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
