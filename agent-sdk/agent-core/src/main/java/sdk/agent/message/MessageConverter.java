package sdk.agent.message;

import java.util.List;

/// Narrows the open transcript to the closed set a provider understands, once per request.
/// Failure terminates the run before the provider is called; there is no unfiltered fallback.
/// A converter that needs I/O is doing `transformContext`'s job.
@FunctionalInterface
public interface MessageConverter {

    List<Message> toLlm(List<AgentMessage> messages);

    /// Keeps the three provider-facing kinds and drops every custom message.
    MessageConverter DEFAULT = messages -> messages.stream()
            .filter(Message.class::isInstance)
            .map(Message.class::cast)
            .toList();
}
