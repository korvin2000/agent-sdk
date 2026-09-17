package sdk.agent.turn;

import java.util.List;
import java.util.OptionalLong;

import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.provider.LlmRequest;

/// What the machine needs from the driver next.
public sealed interface Need {

    /// Open a provider stream for this request, then feed `StreamOpened` (or `StreamFailed`).
    record Stream(LlmRequest request) implements Need { }

    /// Feed the next `Chunk`. The idle timeout is present **only while a tool call is being
    /// assembled** — a long thinking block must not be killed by a tool-call idle timer.
    record Chunk(OptionalLong idleTimeoutMillis) implements Need { }

    /// Ask the hooks for a verdict on the final assistant message, then feed `Verdict`.
    record Verdict(AssistantMessage assistant) implements Need { }

    /// Run some or all of these calls (the driver partitions), then feed `ToolSettled` per call.
    record Tools(List<ContentBlock.ToolCall> pending) implements Need {
        public Tools { pending = List.copyOf(pending); }
    }
}
