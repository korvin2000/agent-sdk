package sdk.agent.message;

import java.util.List;

/// Narrows the open transcript to the closed set a provider understands, once per request.
/// Contract (pi `agent/types.ts:106-107`): must not throw — return a safe fallback instead. The
/// engine wraps it anyway. A converter that needs I/O is doing `transformContext`'s job.
@FunctionalInterface
public interface MessageConverter {

    List<Message> toLlm(List<AgentMessage> messages);

    /// Keeps the three provider-facing kinds and drops every custom message.
    MessageConverter DEFAULT = messages -> messages.stream()
            .filter(Message.class::isInstance)
            .map(Message.class::cast)
            .toList();
}
