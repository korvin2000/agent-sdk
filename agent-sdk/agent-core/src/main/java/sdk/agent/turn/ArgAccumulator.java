package sdk.agent.turn;

import java.util.Objects;

import sdk.agent.json.Json;

/// Per-tool-call argument assembly across deltas, keyed in the turn state by content index for the
/// **whole turn** — a content-block transition never flushes it. `fragments` is concatenated, never
/// parsed incrementally; `initialArguments` is the provider's start-of-call snapshot, used only
/// when no fragment arrives.
public record ArgAccumulator(String toolCallId, String toolName, String fragments, Json initialArguments) {

    public ArgAccumulator {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
        fragments = Objects.requireNonNullElse(fragments, "");
    }

    public ArgAccumulator append(String fragment) {
        return new ArgAccumulator(toolCallId, toolName, fragments + fragment, initialArguments);
    }

    /// A `replace = true` delta discards everything accumulated so far.
    public ArgAccumulator replaced(String fragment) {
        return new ArgAccumulator(toolCallId, toolName, fragment, initialArguments);
    }
}
