package sdk.agent.tool;

import java.util.List;
import java.util.Objects;

import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;

/// What a tool produces. `content` is the only thing the model sees; `details` is host-side and is
/// never sent to a provider on either path. A tool may return `Ok(List.of(), details)` — the funnel
/// normalises empty content to `(no output)` at the single site that builds the transcript entry.
public sealed interface ToolResult {

    List<ContentBlock> content();

    Json details();

    record Ok(List<ContentBlock> content, Json details) implements ToolResult {
        public Ok {
            content = List.copyOf(content);
            details = Objects.requireNonNullElse(details, Json.Null.NULL);
        }
    }

    record Err(ErrorKind kind, List<ContentBlock> content, Json details) implements ToolResult {
        public Err {
            Objects.requireNonNull(kind, "kind");
            content = List.copyOf(content);
            details = Objects.requireNonNullElse(details, Json.Null.NULL);
        }
    }

    static ToolResult text(String text)                    { return new Ok(List.of(ContentBlock.Text.of(text)), Json.Null.NULL); }
    static ToolResult text(String text, Json details)      { return new Ok(List.of(ContentBlock.Text.of(text)), details); }
    static ToolResult error(ErrorKind kind, String text)   { return new Err(kind, List.of(ContentBlock.Text.of(text)), Json.Null.NULL); }
    static ToolResult error(ErrorKind kind, String text, Json details) { return new Err(kind, List.of(ContentBlock.Text.of(text)), details); }

    default boolean isError() { return this instanceof Err; }

    default String text() { return ContentBlock.textOf(content()); }
}
