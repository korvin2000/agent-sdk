package sdk.agent.message;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import sdk.agent.json.Json;

/// One assistant turn, complete. A failed turn is still a message — `stopReason` in
/// `{ERROR, ABORTED}` plus `errorMessage` — never an exception (error-as-data).
public record AssistantMessage(List<ContentBlock> content,
                               ModelRef model,
                               String responseId,
                               Usage usage,
                               StopReason stopReason,
                               String errorMessage,
                               Json providerData,
                               Instant timestamp) implements Message {

    public AssistantMessage {
        content = List.copyOf(content);
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(stopReason, "stopReason");
        providerData = Objects.requireNonNullElse(providerData, Json.Null.NULL);
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /// Computed once here rather than at every consumer.
    public List<ContentBlock.ToolCall> toolCalls() {
        return content.stream()
                .filter(ContentBlock.ToolCall.class::isInstance)
                .map(ContentBlock.ToolCall.class::cast)
                .toList();
    }

    public String text() { return ContentBlock.textOf(content); }

    public boolean terminal() { return stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED; }

    @Override public String kind() { return "assistant"; }
}
