package sdk.agent.provider;

import sdk.agent.concurrent.Cancellation;

/// Unsupported OpenAI transport; this class performs no network I/O. The neutral protocol carries
/// tool calls/results, signed content and opaque [sdk.agent.message.AssistantMessage#providerData]
/// for complete replay items, IDs and encrypted payloads. A transport adapter must perform
/// Responses/Chat Completions and model-specific mapping and feature negotiation; [ThinkingLevel]
/// expresses intent rather than a universal `reasoning.effort` mapping.
public final class OpenAiProvider implements LlmProvider {

    @Override public LlmStream stream(LlmRequest request, Cancellation cancel) {
        throw new UnsupportedOperationException("OpenAI transport is not part of this project");
    }
}
