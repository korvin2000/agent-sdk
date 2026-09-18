package sdk.agent.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import sdk.agent.RunDeps;
import sdk.agent.RunEngine;
import sdk.agent.RunLimits;
import sdk.agent.RunResult;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.AgentHooks;
import sdk.agent.hook.TurnContext;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStream;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ToolMessages;
import sdk.agent.tool.ToolResult;

/// Cancellation and the wall-clock deadline reach work that is **blocked**: the driver thread is
/// interrupted wherever it waits, so a provider that never answers, a hook that never returns and
/// a tool that never finishes all end the run with the right outcome instead of hanging it.
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("RunEngine — deadlines and blocked work")
final class RunDeadlineTest {

    enum BlockedAt { OPEN, READ, TOOL, HOOK }

    @ParameterizedTest
    @EnumSource(BlockedAt.class)
    void theDeadlineInterruptsBlockedWorkAndReportsWallClock(BlockedAt where) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var rig = new Rig().limits(RunLimits.DEFAULTS.withWallClock(Duration.ofSeconds(1)));
        switch (where) {
            case OPEN -> rig.provider((_, _) -> {
                block(entered, release, stopped);
                throw new IOException("fixture released");
            });
            case READ -> rig.provider((_, _) -> new LlmStream() {
                @Override public LlmStreamEvent next() throws IOException { block(entered, release, stopped); return null; }
                @Override public void close() { }
            });
            case TOOL -> rig.provider(ScriptedProvider.defaultScenario()).sequential().tools(
                    FakeTool.named("read").doing(_ -> {
                        try {
                            block(entered, release, stopped);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                        return ToolResult.text("not reached before the deadline");
                    }), FakeTool.named("bash"));
            case HOOK -> rig.hooks(new AgentHooks() {
                @Override public LlmRequest beforeRequest(LlmRequest request, TurnContext context) {
                    try {
                        block(entered, release, stopped);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    return request;
                }
            });
        }
        // The deadline is measured on the deps' clock, so the rig's fake clock is swapped for a real one.
        RunDeps base = rig.deps();
        var clock = Clock.systemUTC();
        var deps = new RunDeps(base.provider(), base.tools(), base.hooks(), base.converter(), base.sink(),
                base.cancel(), base.steering(), base.followUps(), base.requestTemplate(), clock);
        var start = RunEngine.start(List.of(UserMessage.text("hi", clock.instant())), List.of(), rig.limits,
                rig.registry().hash(), "deadline", clock.instant());
        var run = CompletableFuture.supplyAsync(() -> rig.engine().run(start, deps));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            RunResult result = run.get(5, TimeUnit.SECONDS);
            assertEquals(RunOutcome.Limit.WALL_CLOCK, assertInstanceOf(RunOutcome.LimitExceeded.class, result.outcome()).limit());
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "the blocked work was interrupted, not abandoned");
            rig.sink.assertInvariants();
            assertEquals(result.produced(), rig.sink.first(AgentEvent.RunEnd.class).produced());
        } finally {
            release.countDown();
            rig.cancel.cancel();
            run.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a tool that ignores interruption is abandoned and can publish nothing after RunEnd")
    void anAbandonedToolCannotPublishAfterRunEnd() throws Exception {
        var release = new CountDownLatch(1);
        var lateAttempted = new CountDownLatch(1);
        var read = FakeTool.named("read").doing(call -> {
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException _) {
                    // deliberately non-cooperative; released by the test
                }
            }
            call.progress().update(ToolResult.text("late progress"));
            lateAttempted.countDown();
            return ToolResult.text("late result");
        });
        var rig = new Rig().provider(ScriptedProvider.defaultScenario()).sequential().tools(read, FakeTool.named("bash"));
        var run = CompletableFuture.supplyAsync(() -> rig.run("hi"));
        try {
            assertTrue(read.started().await(5, TimeUnit.SECONDS));
            rig.cancel.cancel();
            assertInstanceOf(RunOutcome.Aborted.class, run.get(5, TimeUnit.SECONDS).outcome());
            var eventsAtEnd = rig.sink.events();
            release.countDown();
            assertTrue(lateAttempted.await(5, TimeUnit.SECONDS));
            assertEquals(eventsAtEnd, rig.sink.events(), "no event after RunEnd, whatever the abandoned tool does");
            assertEquals(ToolMessages.CANCELLED, rig.sink.toolResult("call-1").orElseThrow().text());
            assertEquals(ToolMessages.NOT_EXECUTED, rig.sink.toolResult("call-2").orElseThrow().text());
            rig.sink.assertInvariants();
        } finally {
            release.countDown();
            rig.cancel.cancel();
            run.get(5, TimeUnit.SECONDS);
        }
    }

    private static void block(CountDownLatch entered, CountDownLatch release, CountDownLatch stopped) throws IOException {
        entered.countDown();
        try {
            release.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        } finally {
            stopped.countDown();
        }
    }
}
