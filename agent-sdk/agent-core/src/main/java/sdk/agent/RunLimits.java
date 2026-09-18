package sdk.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/// Every field is enforced by the engine; none is decoration. `maxTurns` is checked before a turn
/// opens (and, earlier, by `TurnGuard` as a policy about model behaviour), `maxToolCalls` before a
/// batch launches, `wallClock` at every checkpoint and, through a deadline timer, while a row
/// blocks in provider, hook or tool work.
public record RunLimits(int maxTurns, int maxToolCalls, Optional<Duration> wallClock, ToolExecutionMode toolExecution) {

    public static final RunLimits DEFAULTS = new RunLimits(100, Integer.MAX_VALUE, Optional.empty(), ToolExecutionMode.PARALLEL);

    public RunLimits {
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be >= 1, was " + maxTurns);
        if (maxToolCalls < 0) throw new IllegalArgumentException("maxToolCalls must be >= 0, was " + maxToolCalls);
        wallClock = Objects.requireNonNullElse(wallClock, Optional.empty());
        if (wallClock.filter(d -> !d.isPositive()).isPresent()) throw new IllegalArgumentException("wallClock must be positive, was " + wallClock.get());
        toolExecution = Objects.requireNonNullElse(toolExecution, ToolExecutionMode.PARALLEL);
    }

    public RunLimits withMaxTurns(int n)                  { return new RunLimits(n, maxToolCalls, wallClock, toolExecution); }
    public RunLimits withMaxToolCalls(int n)              { return new RunLimits(maxTurns, n, wallClock, toolExecution); }
    public RunLimits withWallClock(Duration d)            { return new RunLimits(maxTurns, maxToolCalls, Optional.ofNullable(d), toolExecution); }
    public RunLimits withToolExecution(ToolExecutionMode m) { return new RunLimits(maxTurns, maxToolCalls, wallClock, m); }
}
