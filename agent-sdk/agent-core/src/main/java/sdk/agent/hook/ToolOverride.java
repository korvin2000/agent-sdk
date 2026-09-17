package sdk.agent.hook;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolResult;

/// Field-by-field replacement of a tool result; `Optional` so "set to null" and "leave alone"
/// are distinguishable (pi's `??` merge makes clearing `details` impossible). No deep merge.
public record ToolOverride(Optional<List<ContentBlock>> content, Optional<Json> details, Optional<Boolean> isError) {

    public static final ToolOverride NONE = new ToolOverride(Optional.empty(), Optional.empty(), Optional.empty());

    public ToolOverride {
        content = Objects.requireNonNullElse(content, Optional.<List<ContentBlock>>empty()).map(List::copyOf);
        details = Objects.requireNonNullElse(details, Optional.empty());
        isError = Objects.requireNonNullElse(isError, Optional.empty());
    }

    public static ToolOverride content(List<ContentBlock> content) { return new ToolOverride(Optional.of(content), Optional.empty(), Optional.empty()); }
    public static ToolOverride details(Json details)              { return new ToolOverride(Optional.empty(), Optional.of(details), Optional.empty()); }

    /// The one merge site. Flipping `isError` converts between `Ok` and `Err(TOOL_REPORTED)`.
    public ToolResult applyTo(ToolResult original) {
        List<ContentBlock> c = content.orElse(original.content());
        Json d = details.orElse(original.details());
        boolean error = isError.orElse(original.isError());
        if (!error) return new ToolResult.Ok(c, d);
        ErrorKind kind = original instanceof ToolResult.Err e ? e.kind() : ErrorKind.TOOL_REPORTED;
        return new ToolResult.Err(kind, c, d);
    }
}
