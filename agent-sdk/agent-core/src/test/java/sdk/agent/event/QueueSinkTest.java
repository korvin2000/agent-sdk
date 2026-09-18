package sdk.agent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import sdk.agent.message.StopReason;

class QueueSinkTest {

    private static final Instant AT = Instant.parse("2026-09-18T00:00:00Z");

    @Test
    void overflowDropsTheOldestAndKeepsTheTerminalEvent() {
        var sink = new QueueSink(2);
        sink.emit(new AgentEvent.RunStart("run", AgentEvent.RUN_SCOPED, AT));
        sink.emit(new AgentEvent.TurnStart("run", 0, AT));
        sink.emit(new AgentEvent.RunEnd("run", AgentEvent.RUN_SCOPED, AT, List.of(), new RunOutcome.Completed(StopReason.STOP)));
        sink.close();

        List<AgentEvent> retained = sink.stream().toList();
        assertEquals(1, sink.dropped());
        assertInstanceOf(AgentEvent.TurnStart.class, retained.getFirst());
        assertInstanceOf(AgentEvent.RunEnd.class, retained.getLast());
    }

    @Test
    void closingTheStreamWakesAWaitingConsumer() throws Exception {
        var sink = new QueueSink();
        var stream = sink.stream();
        var consumer = stream.iterator();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = executor.submit(consumer::hasNext);
            stream.close();
            assertFalse(waiting.get(2, TimeUnit.SECONDS));
        }
        assertThrows(IllegalStateException.class, sink::stream, "single-consumer");
    }

    @Test
    void theProducerNeverWaitsForTheConsumer() {
        var sink = new QueueSink(1);
        try (var _ = sink.stream()) {                                  // a consumer attached but never reading
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 10_000; i++) sink.emit(new AgentEvent.TurnStart("run", i, AT));
            });
        }
        assertEquals(9_999, sink.dropped());
    }
}
