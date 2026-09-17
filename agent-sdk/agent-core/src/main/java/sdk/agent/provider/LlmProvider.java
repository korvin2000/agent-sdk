package sdk.agent.provider;

import java.io.IOException;

import sdk.agent.concurrent.Cancellation;

/// The provider seam. The core owns the protocol ([LlmStreamEvent]); a provider implements it.
///
/// Contract: [#stream] must **not** throw for request, model or runtime failures — encode them as
/// [LlmStreamEvent.Failed]. It may throw only for programmer error. Retry policy, auth, transport
/// and token counting all live behind this interface; the loop knows nothing about any of them.
public interface LlmProvider {

    LlmStream stream(LlmRequest request, Cancellation cancel) throws IOException;
}
