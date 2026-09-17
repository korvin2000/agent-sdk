package sdk.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.function.BiConsumer;

import sdk.agent.event.AgentListener;
import sdk.agent.event.ListenerFanout;
import sdk.agent.hook.AgentHooks;
import sdk.agent.hook.CompositeHooks;
import sdk.agent.hook.TurnGuard;
import sdk.agent.json.Json;
import sdk.agent.message.AgentMessageCodec;
import sdk.agent.message.MessageConverter;
import sdk.agent.message.ModelRef;
import sdk.agent.prompt.PromptContributor;
import sdk.agent.prompt.SectionSpec;
import sdk.agent.prompt.SystemPromptBuilder;
import sdk.agent.prompt.SystemPromptOverride;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.StubProvider;
import sdk.agent.provider.ThinkingLevel;
import sdk.agent.spi.Contributions;
import sdk.agent.spi.Extension;
import sdk.agent.tool.ToolProvider;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.turn.TurnMachine;

/// Assembles an [Agent] from a provider and extensions. `contributions()` is called exactly once
/// per extension, in registration order; tool names, section ids and message codecs are validated
/// here — fail-closed, naming both owners on any collision.
public final class AgentBuilder {

    private static final System.Logger LOG = System.getLogger(AgentBuilder.class.getName());

    private LlmProvider provider;
    private final List<Extension> extensions = new ArrayList<>();
    private final List<AgentHooks> hooks = new ArrayList<>();
    private AgentHooks turnGuard = TurnGuard.defaults();
    private boolean discover;
    private SystemPromptOverride promptOverride;
    private RunLimits limits = RunLimits.DEFAULTS;
    private ModelRef model = ModelRef.UNSET;
    private ThinkingLevel thinking = ThinkingLevel.OFF;
    private OptionalInt maxOutputTokens = OptionalInt.empty();
    private Json providerOptions = Json.Obj.EMPTY;
    private Clock clock = Clock.systemUTC();
    private MessageConverter converter = MessageConverter.DEFAULT;
    private Duration toolIdleTimeout = TurnMachine.DEFAULT_TOOL_IDLE_TIMEOUT;
    private BiConsumer<AgentListener, Throwable> onListenerError =
            (l, t) -> LOG.log(System.Logger.Level.WARNING, "agent listener " + l + " threw", t);
    private BiConsumer<String, Throwable> onHookError =
            (name, t) -> LOG.log(System.Logger.Level.WARNING, "hook " + name + " threw", t);

    private AgentBuilder() { }

    public static AgentBuilder create() { return new AgentBuilder(); }

    /// The one collaborator with no meaningful default; absent, a [StubProvider] keeps the agent runnable.
    public AgentBuilder provider(LlmProvider p)                 { provider = Objects.requireNonNull(p); return this; }
    public AgentBuilder extension(Extension e)                  { extensions.add(Objects.requireNonNull(e)); return this; }
    /// `ServiceLoader` discovery — opt-in, never default: a host must be able to state which extensions are live.
    public AgentBuilder discoverExtensions()                    { discover = true; return this; }
    public AgentBuilder hook(AgentHooks h)                      { hooks.add(Objects.requireNonNull(h)); return this; }
    /// Replace the default [TurnGuard]; `null` removes loop/budget policy entirely.
    public AgentBuilder turnGuard(AgentHooks guard)             { turnGuard = guard; return this; }
    /// The host's last word on the system prompt: replace the assembled one, or append to it.
    public AgentBuilder systemPromptOverride(SystemPromptOverride o) { promptOverride = o; return this; }
    public AgentBuilder limits(RunLimits l)                     { limits = Objects.requireNonNull(l); return this; }
    public AgentBuilder model(ModelRef m)                       { model = Objects.requireNonNull(m); return this; }
    public AgentBuilder thinking(ThinkingLevel t)               { thinking = Objects.requireNonNull(t); return this; }
    public AgentBuilder maxOutputTokens(int n)                  { maxOutputTokens = OptionalInt.of(n); return this; }
    public AgentBuilder providerOptions(Json options)           { providerOptions = Objects.requireNonNull(options); return this; }
    public AgentBuilder clock(Clock c)                          { clock = Objects.requireNonNull(c); return this; }
    public AgentBuilder converter(MessageConverter c)           { converter = Objects.requireNonNull(c); return this; }
    public AgentBuilder toolIdleTimeout(Duration d)             { toolIdleTimeout = Objects.requireNonNull(d); return this; }
    public AgentBuilder onListenerError(BiConsumer<AgentListener, Throwable> h) { onListenerError = Objects.requireNonNull(h); return this; }
    public AgentBuilder onHookError(BiConsumer<String, Throwable> h)            { onHookError = Objects.requireNonNull(h); return this; }

    public Agent build() {
        List<Extension> all = new ArrayList<>(extensions);
        if (discover) ServiceLoader.load(Extension.class).forEach(all::add);

        var providers = new ArrayList<ToolProvider>();
        var sectionsByContributor = new LinkedHashMap<String, List<SectionSpec>>();
        var contributedHooks = new ArrayList<AgentHooks>();
        var codecs = new LinkedHashMap<String, AgentMessageCodec>();
        var codecOwners = new LinkedHashMap<String, String>();
        var contributedProviders = new ArrayList<LlmProvider>();

        for (Extension e : all) {
            Contributions c = Objects.requireNonNull(e.contributions(), () -> "extension " + e.id() + " returned null contributions");
            if (!c.tools().isEmpty()) providers.add(ToolProvider.of(e.id(), c.tools()));
            providers.addAll(c.toolProviders());
            var sections = sectionsByContributor.computeIfAbsent(e.id(), _ -> new ArrayList<>());
            for (PromptContributor pc : c.promptContributors()) sections.addAll(pc.sections());
            contributedHooks.addAll(c.hooks());
            for (AgentMessageCodec codec : c.messageCodecs()) {
                String previous = codecOwners.putIfAbsent(codec.kind(), e.id());
                if (previous != null) {
                    throw new IllegalStateException("message kind '%s' has codecs from both '%s' and '%s'".formatted(codec.kind(), previous, e.id()));
                }
                codecs.put(codec.kind(), codec);
            }
            c.provider().ifPresent(contributedProviders::add);
        }

        ToolRegistry.of(providers);                                                 // fail-closed on collisions, now
        SystemPromptBuilder.validate(sectionsByContributor);                        // unique section ids
        List<SectionSpec> sections = sectionsByContributor.values().stream().flatMap(List::stream).toList();

        var allHooks = new ArrayList<AgentHooks>();
        if (turnGuard != null) allHooks.add(turnGuard);
        allHooks.addAll(hooks);
        allHooks.addAll(contributedHooks);

        var template = new LlmRequest(model, "", List.of(), List.of(), thinking, maxOutputTokens, providerOptions);
        var config = new Agent.Config(chooseProvider(contributedProviders), List.copyOf(providers),
                new CompositeHooks(allHooks, onHookError), converter, Map.copyOf(codecs), sections,
                Optional.ofNullable(promptOverride), template, limits, clock, toolIdleTimeout, List.copyOf(all));
        return new Agent(config, new ListenerFanout(onListenerError));
    }

    private LlmProvider chooseProvider(List<LlmProvider> contributed) {
        if (provider != null) return provider;
        if (contributed.isEmpty()) return new StubProvider();
        if (contributed.size() == 1) return contributed.getFirst();
        throw new IllegalStateException("several extensions contribute a provider; choose one with AgentBuilder.provider(...)");
    }
}
