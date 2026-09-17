package sdk.agent;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.EventSink;
import sdk.agent.hook.AgentHooks;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.MessageConverter;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.tool.ToolRegistry;

/// The **non-serialisable** collaborators, supplied fresh on every `advance()`. `requestTemplate`
/// carries the model, system prompt, thinking level and provider options; the engine fills in the
/// messages and tool specs per turn.
public record RunDeps(LlmProvider provider,
                      ToolRegistry tools,
                      AgentHooks hooks,
                      MessageConverter converter,
                      EventSink sink,
                      Cancellation cancel,
                      Supplier<List<AgentMessage>> steering,
                      Supplier<List<AgentMessage>> followUps,
                      LlmRequest requestTemplate,
                      Clock clock) {

    public RunDeps {
        Objects.requireNonNull(provider, "provider");
        tools = Objects.requireNonNullElse(tools, ToolRegistry.EMPTY);
        hooks = Objects.requireNonNullElse(hooks, AgentHooks.NONE);
        converter = Objects.requireNonNullElse(converter, MessageConverter.DEFAULT);
        sink = Objects.requireNonNullElse(sink, EventSink.NONE);
        cancel = Objects.requireNonNullElse(cancel, Cancellation.create());
        steering = Objects.requireNonNullElse(steering, List::of);
        followUps = Objects.requireNonNullElse(followUps, List::of);
        Objects.requireNonNull(requestTemplate, "requestTemplate");
        clock = Objects.requireNonNullElse(clock, Clock.systemUTC());
    }
}
