package sdk.agent.provider;

import sdk.agent.concurrent.Cancellation;

/// Placeholder for the Anthropic Messages API transport, which is not part of this project. The
/// core protocol already fits it one to one: `content_block_start/delta/stop` map onto the
/// `TextStart/Delta/End`, `ThinkingStart/Delta/End` (signature and `redacted_thinking` carried in
/// [sdk.agent.message.ContentBlock.Thinking]) and `ToolCallStart/Delta` events, `message_delta`
/// onto [LlmStreamEvent.Done] with its [sdk.agent.message.Usage], [ThinkingLevel] onto
/// `output_config.effort`, and a `ToolResultMessage` onto a `tool_result` block.
public final class AnthropicProvider implements LlmProvider {

    @Override public LlmStream stream(LlmRequest request, Cancellation cancel) {
        throw new UnsupportedOperationException("Anthropic transport is not part of this project");
    }
}
