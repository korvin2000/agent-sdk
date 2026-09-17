package sdk.agent;

import java.util.Objects;

/// Everything `RunEngine.start` needs that is not a message and not a collaborator.
/// `skipInitialSteeringPoll` is pi's `continue()` fallback made an explicit field.
public record RunOptions(RunLimits limits, boolean skipInitialSteeringPoll, String toolSetHash) {

    public RunOptions { limits = Objects.requireNonNullElse(limits, RunLimits.DEFAULTS); }

    public static RunOptions defaults() { return new RunOptions(RunLimits.DEFAULTS, false, null); }

    public RunOptions withLimits(RunLimits l)          { return new RunOptions(l, skipInitialSteeringPoll, toolSetHash); }
    public RunOptions withToolSetHash(String h)        { return new RunOptions(limits, skipInitialSteeringPoll, h); }
    public RunOptions skippingInitialSteeringPoll()    { return new RunOptions(limits, true, toolSetHash); }
}
