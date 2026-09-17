package sdk.agent.message;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import sdk.agent.json.Json;

/// The transcript entry paired with one tool call. `content` is the only thing the model sees;
/// `details` is host-side ([Json], not `Object`, because the transcript must serialise) and is
/// never sent to a provider.
public record ToolResultMessage(String toolCallId,
                                String toolName,
                                List<ContentBlock> content,
                                Json details,
                                boolean isError,
                                Instant timestamp) implements Message {

    public ToolResultMessage {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
        content = List.copyOf(content);
        details = details == null ? Json.Null.NULL : details;
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public String text() { return ContentBlock.textOf(content); }

    @Override public String kind() { return "toolResult"; }
}
