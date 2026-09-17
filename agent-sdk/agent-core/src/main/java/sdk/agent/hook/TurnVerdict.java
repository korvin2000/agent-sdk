package sdk.agent.hook;

import java.util.Objects;

import sdk.agent.event.RunOutcome;
import sdk.agent.message.AgentMessage;

/// What [AgentHooks#afterAssistant] decides. `Retry` skips tool execution and starts another turn
/// with `message` injected; `Stop` ends the run — its `message`, if any, is appended before `RunEnd`.
public sealed interface TurnVerdict {

    TurnVerdict PROCEED = new Proceed();

    record Proceed() implements TurnVerdict { }

    record Retry(AgentMessage message, String reason) implements TurnVerdict {
        public Retry { Objects.requireNonNull(message, "message"); reason = Objects.requireNonNullElse(reason, ""); }
    }

    /// `message` may be null: there is nothing to say to a model that will not be called again.
    record Stop(RunOutcome outcome, AgentMessage message) implements TurnVerdict {
        public Stop { Objects.requireNonNull(outcome, "outcome"); }
    }
}
