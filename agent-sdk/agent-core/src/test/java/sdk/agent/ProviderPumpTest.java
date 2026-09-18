package sdk.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.message.ModelRef;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStream;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.provider.ThinkingLevel;

class ProviderPumpTest {

    private static final LlmRequest REQUEST = new LlmRequest(
            new ModelRef("provider", "provider", "model", 1_000, 1_000),
            "", List.of(), List.of(), ThinkingLevel.OFF, OptionalInt.empty(), null);

    @Test
    void cancellationWakesAwaitOpenWhenProviderIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var provider = (LlmProvider) (request, cancel) -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return new OneEventStream();
        };
        var cancellation = Cancellation.create();
        try (var pump = new ProviderPump(provider, REQUEST, cancellation)) {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var failure = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try {
                    pump.awaitOpen();
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
            cancellation.cancel();
            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.get());
            release.countDown();
        }
    }

    @Test
    void cancellationWakesNextWhenProviderReadIsBlocked() throws Exception {
        var stream = new BlockingReadStream();
        var cancellation = Cancellation.create();
        LlmProvider provider = (request, cancel) -> stream;
        try (var pump = new ProviderPump(provider, REQUEST, cancellation)) {
            pump.awaitOpen();
            var failure = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try {
                    pump.next(OptionalLong.empty());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
            assertTrue(stream.readStarted.await(1, TimeUnit.SECONDS));
            cancellation.cancel();
            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.get());
        }
        assertTrue(stream.closed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void streamReturnedAfterCancellationIsClosed() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stream = new OneEventStream();
        LlmProvider provider = (request, cancel) -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return stream;
        };
        var cancellation = Cancellation.create();
        var pump = new ProviderPump(provider, REQUEST, cancellation);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        cancellation.cancel();
        release.countDown();
        assertTrue(stream.closed.await(1, TimeUnit.SECONDS));
        pump.close();
    }

    @Test
    void terminalEventStopsFurtherProviderPulls() throws Exception {
        var pulls = new AtomicInteger();
        var stream = new LlmStream() {
            @Override public LlmStreamEvent next() {
                return pulls.getAndIncrement() == 0
                        ? new LlmStreamEvent.Done(sdk.agent.message.StopReason.STOP, sdk.agent.message.Usage.EMPTY, "response", sdk.agent.json.Json.nil())
                        : new LlmStreamEvent.TextDelta(0, "late");
            }
            @Override public void close() { }
        };
        try (var pump = new ProviderPump((request, cancel) -> stream, REQUEST, Cancellation.create())) {
            pump.awaitOpen();
            assertInstanceOf(LlmStreamEvent.Done.class, pump.next(OptionalLong.empty()));
            assertEquals(1, pulls.get());
            assertNull(pump.next(OptionalLong.empty()));
            assertEquals(1, pulls.get());
        }
    }

    @Test
    void openingFailureIsPropagatedFromAwaitOpen() {
        var failure = new IOException("open failed");
        try (var pump = new ProviderPump((request, cancel) -> { throw failure; },
                REQUEST, Cancellation.create())) {
            IOException observed = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, pump::awaitOpen);
            assertSame(failure, observed);
        }
    }

    @Test
    void readFailureAfterOpenIsReportedByNext() throws Exception {
        var failure = new IOException("read failed");
        try (var pump = new ProviderPump((request, cancel) -> new LlmStream() {
            @Override public LlmStreamEvent next() throws IOException { throw failure; }
            @Override public void close() { }
        }, REQUEST, Cancellation.create())) {
            pump.awaitOpen();
            assertSame(failure, org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> pump.next(OptionalLong.empty())));
        }
    }


    private static void awaitIgnoringInterrupt(CountDownLatch release) {
        for (;;) {
            try {
                if (release.await(50, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException _) {
                // Cancellation interrupts provider work, but the fixture deliberately keeps ownership
                // until the test releases it so late-open cleanup is exercised.
            }
        }
    }

    private static class OneEventStream implements LlmStream {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public LlmStreamEvent next() { return null; }
        @Override public void close() { closed.countDown(); }
    }

    private static final class BlockingReadStream implements LlmStream {
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public LlmStreamEvent next() {
            readStarted.countDown();
            awaitIgnoringInterrupt(closed);
            return null;
        }

        @Override public void close() { closed.countDown(); }
    }
}
