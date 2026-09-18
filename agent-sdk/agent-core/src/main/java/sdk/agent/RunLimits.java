package sdk.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/// Engine-enforced budgets: turns before opening a provider request, calls before a batch,
/// and wall time at phase boundaries and during cancellable work.
public record RunLimits(int maxTurns, int maxToolCalls, Optional<Duration> wallClock, ToolExecutionMode toolExecution) {

    public static final RunLimits DEFAULTS = new RunLimits(100, Integer.MAX_VALUE, Optional.empty(), ToolExecutionMode.PARALLEL);

    public RunLimits {
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be >= 1, was " + maxTurns);
        if (maxToolCalls < 0) throw new IllegalArgumentException("maxToolCalls must be >= 0, was " + maxToolCalls);
        wallClock = Objects.requireNonNullElse(wallClock, Optional.empty());
        if (wallClock.isPresent() && (wallClock.get().isZero() || wallClock.get().isNegative())) {
            throw new IllegalArgumentException("wallClock must be positive");
        }
        toolExecution = Objects.requireNonNullElse(toolExecution, ToolExecutionMode.PARALLEL);
    }

    public RunLimits withMaxTurns(int n)                  { return new RunLimits(n, maxToolCalls, wallClock, toolExecution); }
    public RunLimits withMaxToolCalls(int n)              { return new RunLimits(maxTurns, n, wallClock, toolExecution); }
    public RunLimits withWallClock(Duration d)            { return new RunLimits(maxTurns, maxToolCalls, Optional.ofNullable(d), toolExecution); }
    public RunLimits withToolExecution(ToolExecutionMode m) { return new RunLimits(maxTurns, maxToolCalls, wallClock, m); }
}
