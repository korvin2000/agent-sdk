package sdk.agent.turn;

import java.util.Objects;

import sdk.agent.json.Json;

/// **whole turn** — a content-block transition never flushes it. A null `fragments` means no
/// fragment event arrived; an empty string means an empty fragment did arrive and is authoritative.
public record ArgAccumulator(String toolCallId, String toolName, String fragments, Json initialArguments) {

    public ArgAccumulator {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
    }

    public ArgAccumulator append(String fragment) {
        Objects.requireNonNull(fragment, "fragment");
        return new ArgAccumulator(toolCallId, toolName, fragments == null ? fragment : fragments + fragment, initialArguments);
    }

    /// A `replace = true` delta discards everything accumulated so far.
    public ArgAccumulator replaced(String fragment) {
        return new ArgAccumulator(toolCallId, toolName, Objects.requireNonNull(fragment, "fragment"), initialArguments);
    }
}
