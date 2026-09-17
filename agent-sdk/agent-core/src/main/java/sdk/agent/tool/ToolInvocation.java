package sdk.agent.tool;

import java.util.Objects;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;

/// Everything one tool call receives, as one record — so a later field does not break every tool.
/// `cancel` is passed explicitly because tools are third-party code and must not depend on a
/// scoped binding being present.
///
/// @param <P> the bound parameter type
public record ToolInvocation<P>(String toolCallId,
                                String toolName,
                                P params,
                                Json rawArguments,
                                Cancellation cancel,
                                ProgressSink progress) {

    public ToolInvocation {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
        rawArguments = Objects.requireNonNullElse(rawArguments, Json.Obj.EMPTY);
        Objects.requireNonNull(cancel, "cancel");
        progress = Objects.requireNonNullElse(progress, ProgressSink.NONE);
    }
}
