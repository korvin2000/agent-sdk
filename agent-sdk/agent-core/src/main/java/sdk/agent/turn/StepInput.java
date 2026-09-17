package sdk.agent.turn;

import java.util.Objects;

import sdk.agent.hook.TurnVerdict;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStreamEvent;

/// Everything the outside world can tell the turn. Cancellation is an input like any other, so
/// there is no chunk-vs-cancel race to reason about.
public sealed interface StepInput {

    record Begin(LlmRequest request) implements StepInput {
        public Begin { Objects.requireNonNull(request, "request"); }
    }

    record StreamOpened() implements StepInput { }

    record Chunk(LlmStreamEvent event) implements StepInput {
        public Chunk { Objects.requireNonNull(event, "event"); }
    }

    /// The stream ended with no terminal event.
    record StreamExhausted() implements StepInput { }

    record StreamFailed(String message, boolean aborted) implements StepInput {
        public StreamFailed { message = Objects.requireNonNullElse(message, "provider stream failed"); }
    }

    /// No chunk arrived within the tool-call idle timeout → the turn is stalled.
    record IdleTimedOut() implements StepInput { }

    /// Abort, or "finish this turn without running anything else": open slots are padded.
    record Cancel() implements StepInput { }

    record Verdict(TurnVerdict verdict) implements StepInput {
        public Verdict { Objects.requireNonNull(verdict, "verdict"); }
    }

    record ToolSettled(String toolCallId, ToolResultMessage result) implements StepInput {
        public ToolSettled { Objects.requireNonNull(toolCallId, "toolCallId"); Objects.requireNonNull(result, "result"); }
    }
}
