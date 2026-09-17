package sdk.agent;

import java.util.List;
import java.util.Objects;

import sdk.agent.event.RunOutcome;
import sdk.agent.message.AgentMessage;

/// What `run()` returns and what the run future completes with — the same outcome the
/// subscriber saw in `RunEnd`, so the two channels can never disagree.
public record RunResult(RunState state, RunOutcome outcome) {

    public RunResult { Objects.requireNonNull(state, "state"); Objects.requireNonNull(outcome, "outcome"); }

    public List<AgentMessage> produced() { return state.produced(); }

    public boolean isSuccess() { return outcome.isSuccess(); }
}
