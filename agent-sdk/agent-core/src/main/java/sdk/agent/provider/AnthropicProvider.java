package sdk.agent.provider;

import sdk.agent.concurrent.Cancellation;

/// Unsupported Anthropic transport; this class performs no network I/O. The neutral protocol
/// carries tool calls/results, thinking signatures/redaction and opaque
/// [sdk.agent.message.AssistantMessage#providerData] for provider replay metadata. A transport
/// adapter must perform Messages API and model-specific mapping and feature negotiation;
/// [ThinkingLevel] expresses intent rather than a universal `output_config.effort` mapping.
public final class AnthropicProvider implements LlmProvider {

    @Override public LlmStream stream(LlmRequest request, Cancellation cancel) {
        throw new UnsupportedOperationException("Anthropic transport is not part of this project");
    }
}
