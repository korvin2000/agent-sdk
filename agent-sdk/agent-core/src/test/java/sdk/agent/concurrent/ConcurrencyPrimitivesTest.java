package sdk.agent.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ConcurrencyPrimitivesTest {

    @Test
    void listenersRunOnceInOrderAndLateRegistrationFiresImmediately() {
        var c = Cancellation.create();
        var order = new ArrayList<String>();
        c.onCancel(() -> order.add("a"));
        c.onCancel(() -> { throw new RuntimeException("boom"); });
        c.onCancel(() -> order.add("b"));
        c.cancel();
        c.cancel();
        assertEquals(List.of("a", "b"), order);
        c.onCancel(() -> order.add("late"));
        assertEquals(List.of("a", "b", "late"), order);
        assertTrue(c.isCancelled());
    }

    @Test
    void registrationCloseDeregisters() {
        var c = Cancellation.create();
        var count = new AtomicInteger();
        Cancellation.Registration reg = c.onCancel(count::incrementAndGet);
        reg.close();
        c.cancel();
        assertEquals(0, count.get());
    }

    @Test
    void forkJoinUntilTimesOutAndCloseNeverHangs() throws Exception {
        var leaked = new ArrayList<String>();
        var started = new CountDownLatch(1);
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        Instant before = Instant.now();
        try (var fork = Fork.open(Duration.ofMillis(200), leaked::add)) {
            fork.fork("stubborn", () -> {
                started.countDown();
                while (!stop.get()) Thread.onSpinWait();          // ignores interrupt on purpose
                return null;
            });
            started.await();
            assertThrows(TimeoutException.class, () -> fork.joinUntil(Instant.now().plusMillis(50)));
        } finally {
            stop.set(true);
        }
        assertTrue(Duration.between(before, Instant.now()).toMillis() < 5_000, "close() must be bounded by the grace period");
        assertEquals(List.of("stubborn"), leaked);
    }

    @Test
    void forkCancelAllInterruptsTasks() throws Exception {
        try (var fork = Fork.open()) {
            var handle = fork.fork(() -> { Thread.sleep(10_000); return "done"; });
            fork.cancelAll();
            assertThrows(java.util.concurrent.CancellationException.class, handle::get);
        }
    }
}
