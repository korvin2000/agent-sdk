package sdk.agent;

import java.util.function.Function;

import sdk.agent.event.RunOutcome;

/// What one `advance()` returns. Forward-compatible callers use [#fold] or
/// `if (step instanceof Step.Done d)` rather than an exhaustive switch.
public sealed interface Step {

    RunState state();

    record Continue(RunState state) implements Step { }

    record Done(RunState state, RunOutcome outcome) implements Step { }

    default <R> R fold(Function<Continue, R> onContinue, Function<Done, R> onDone) {
        return this instanceof Continue c ? onContinue.apply(c) : onDone.apply((Done) this);
    }
}
