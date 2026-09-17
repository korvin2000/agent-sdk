package sdk.agent.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/// A [Clock] the test moves by hand. The engine reads the clock at exactly two kinds of place —
/// every event's `at`, and the wall-clock checkpoints of §4.3.2 — so pinning it makes both
/// deterministic: a wall-clock limit test needs no sleeping and event timestamps are golden-able.
///
/// Reads and writes are `volatile`, because the run drives on another thread than the test.
public final class FakeClock extends Clock {

    /// A fixed, obviously-synthetic starting point.
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private final ZoneId zone;
    private volatile Instant now;

    private FakeClock(ZoneId zone, Instant now) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.now = Objects.requireNonNull(now, "now");
    }

    public static FakeClock atEpoch()             { return new FakeClock(ZoneOffset.UTC, EPOCH); }

    public static FakeClock at(Instant start)     { return new FakeClock(ZoneOffset.UTC, start); }

    @Override public ZoneId getZone()             { return zone; }

    @Override public Clock withZone(ZoneId other) { return new FakeClock(other, now); }

    @Override public Instant instant()            { return now; }

    /// Moves time forward (or back, with a negative duration).
    public FakeClock advance(Duration by) {
        now = now.plus(Objects.requireNonNull(by, "by"));
        return this;
    }

    public FakeClock set(Instant to) {
        now = Objects.requireNonNull(to, "to");
        return this;
    }

    /// How far this clock has moved from where it started reading — handy in an assertion message.
    @Override public String toString() { return "FakeClock[" + now + "]"; }
}
