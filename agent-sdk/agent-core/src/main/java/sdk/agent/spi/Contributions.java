package sdk.agent.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import sdk.agent.hook.AgentHooks;
import sdk.agent.message.AgentMessageCodec;
import sdk.agent.prompt.PromptContributor;
import sdk.agent.provider.LlmProvider;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolProvider;

/// What an [Extension] declares. Deeply immutable; the builder holds it for the agent's lifetime.
public record Contributions(List<Tool<?>> tools,
                            List<ToolProvider> toolProviders,
                            List<PromptContributor> promptContributors,
                            List<AgentHooks> hooks,
                            List<AgentMessageCodec> messageCodecs,
                            Optional<LlmProvider> provider) {

    public static final Contributions NONE = builder().build();

    public Contributions {
        tools = List.copyOf(tools);
        toolProviders = List.copyOf(toolProviders);
        promptContributors = List.copyOf(promptContributors);
        hooks = List.copyOf(hooks);
        messageCodecs = List.copyOf(messageCodecs);
        provider = Objects.requireNonNullElse(provider, Optional.empty());
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Tool<?>> tools = new ArrayList<>();
        private final List<ToolProvider> toolProviders = new ArrayList<>();
        private final List<PromptContributor> promptContributors = new ArrayList<>();
        private final List<AgentHooks> hooks = new ArrayList<>();
        private final List<AgentMessageCodec> messageCodecs = new ArrayList<>();
        private LlmProvider provider;

        private Builder() { }

        public Builder tool(Tool<?> tool)                          { tools.add(Objects.requireNonNull(tool)); return this; }
        public Builder tools(List<? extends Tool<?>> list)         { tools.addAll(list); return this; }
        public Builder toolProvider(ToolProvider p)                { toolProviders.add(Objects.requireNonNull(p)); return this; }
        public Builder promptContributor(PromptContributor c)      { promptContributors.add(Objects.requireNonNull(c)); return this; }
        public Builder hook(AgentHooks h)                          { hooks.add(Objects.requireNonNull(h)); return this; }
        public Builder messageCodec(AgentMessageCodec c)           { messageCodecs.add(Objects.requireNonNull(c)); return this; }
        public Builder provider(LlmProvider p)                     { provider = Objects.requireNonNull(p); return this; }

        public Contributions build() {
            return new Contributions(tools, toolProviders, promptContributors, hooks, messageCodecs, Optional.ofNullable(provider));
        }
    }
}
