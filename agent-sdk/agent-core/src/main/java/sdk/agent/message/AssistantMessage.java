package sdk.agent.message;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// One assistant turn, complete. A failed turn is still a message — `stopReason` in
/// `{ERROR, ABORTED}` plus `errorMessage` — never an exception (error-as-data).
public record AssistantMessage(List<ContentBlock> content,
                               ModelRef model,
                               String responseId,
                               Usage usage,
                               StopReason stopReason,
                               String errorMessage,
                               Instant timestamp) implements Message {

    public AssistantMessage {
        content = List.copyOf(content);
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /// Computed once here rather than at every consumer (pi recomputes it at `agent-loop.ts:201` and `:343`).
    public List<ContentBlock.ToolCall> toolCalls() {
        return content.stream()
                .filter(ContentBlock.ToolCall.class::isInstance)
                .map(ContentBlock.ToolCall.class::cast)
                .toList();
    }

    public String text() { return ContentBlock.textOf(content); }

    public boolean terminal() { return stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED; }

    public static AssistantMessage failed(ModelRef model, StopReason reason, String message, Instant at) {
        return new AssistantMessage(List.of(), model, null, Usage.EMPTY, reason, message, at);
    }

    @Override public String kind() { return "assistant"; }
}
