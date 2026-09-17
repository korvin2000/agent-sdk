package sdk.agent.hook;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import sdk.agent.RunLimits;
import sdk.agent.concurrent.Cancellation;
import sdk.agent.message.AgentMessage;
import sdk.agent.tool.ToolRegistry;

/// The read-only per-turn view handed to every hook.
public record TurnContext(String runId,
                          int turnIndex,
                          List<AgentMessage> transcript,
                          RunLimits limits,
                          int turnsUsed,
                          ToolRegistry tools,
                          Cancellation cancel,
                          Clock clock) {

    public TurnContext {
        Objects.requireNonNull(runId, "runId");
        transcript = List.copyOf(transcript);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(clock, "clock");
    }
}
