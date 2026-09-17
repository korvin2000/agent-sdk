package sdk.agent.turn;

import java.util.List;
import java.util.Objects;

import sdk.agent.message.AssistantMessage;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;

/// What a finished turn yields to the run layer. `toolResults` is index-aligned with
/// `assistant.toolCalls()`; unrun slots are padded.
public record TurnResult(AssistantMessage assistant, List<ToolResultMessage> toolResults,
                         StopReason stopReason, Usage usage, boolean stalled) {

    public TurnResult {
        Objects.requireNonNull(assistant, "assistant");
        toolResults = List.copyOf(toolResults);
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
    }

    public boolean hadToolCalls() { return !assistant.toolCalls().isEmpty(); }
}
