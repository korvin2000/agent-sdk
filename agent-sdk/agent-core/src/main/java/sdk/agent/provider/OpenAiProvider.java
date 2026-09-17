package sdk.agent.provider;

import sdk.agent.concurrent.Cancellation;

/// Placeholder for the OpenAI Responses / Chat Completions transport, which is not part of this
/// project. The core protocol already fits it one to one: streamed `function_call` items map onto
/// `ToolCallStart` (id, name) plus `ToolCallDelta` argument fragments, reasoning items with
/// `encrypted_content` onto [sdk.agent.message.ContentBlock.Thinking] with its `signature`,
/// `finish_reason` onto [sdk.agent.message.StopReason], [ThinkingLevel] onto `reasoning.effort`,
/// and a `ToolResultMessage` onto a `function_call_output` / `role: tool` message.
public final class OpenAiProvider implements LlmProvider {

    @Override public LlmStream stream(LlmRequest request, Cancellation cancel) {
        throw new UnsupportedOperationException("OpenAI transport is not part of this project");
    }
}
