package sdk.agent;

import sdk.agent.event.RunOutcome;

/// What one `advance()` returns.
public sealed interface Step {

    RunState state();

    record Continue(RunState state) implements Step { }

    record Done(RunState state, RunOutcome outcome) implements Step { }
}
