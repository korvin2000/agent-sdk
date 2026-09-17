package sdk.agent.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.AgentEvent;
import sdk.agent.json.Json;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.tool.ToolResult;

/// Folds a list of hooks into one, in registration order, with every call site individually
/// guarded: a throwing hook is reported to `onHookError` and degrades to its stated fallback —
/// "the rewrite did not apply", never "the tool call vanished".
///
/// Composition rules: `transformContext`, `beforeRequest` compose left to right (each sees the
/// previous output); `beforeTurn` concatenates; `afterAssistant` returns the first non-`Proceed`;
/// `beforeToolCall` short-circuits on the first `Block` and chains rewrites; `afterToolCall`
/// chains overrides so a later hook sees the earlier one's result.
public final class CompositeHooks implements AgentHooks {

    private static final System.Logger LOG = System.getLogger(CompositeHooks.class.getName());

    private final List<AgentHooks> hooks;
    private final BiConsumer<String, Throwable> onHookError;

    public CompositeHooks(List<? extends AgentHooks> hooks, BiConsumer<String, Throwable> onHookError) {
        this.hooks = List.copyOf(hooks);
        this.onHookError = Objects.requireNonNull(onHookError, "onHookError");
    }

    public static CompositeHooks of(List<? extends AgentHooks> hooks) {
        return new CompositeHooks(hooks, (name, t) -> LOG.log(System.Logger.Level.WARNING, "hook " + name + " threw", t));
    }

    public List<AgentHooks> hooks() { return hooks; }

    @Override public List<AgentMessage> transformContext(List<AgentMessage> messages, Cancellation cancel) {
        List<AgentMessage> current = messages;
        for (AgentHooks h : hooks) {
            List<AgentMessage> in = current;
            current = safely("transformContext", () -> h.transformContext(in, cancel), in);
        }
        return current;
    }

    @Override public List<AgentMessage> beforeTurn(TurnContext ctx) {
        var out = new ArrayList<AgentMessage>();
        for (AgentHooks h : hooks) out.addAll(safely("beforeTurn", () -> h.beforeTurn(ctx), List.of()));
        return out;
    }

    @Override public LlmRequest beforeRequest(LlmRequest request, TurnContext ctx) {
        LlmRequest current = request;
        for (AgentHooks h : hooks) {
            LlmRequest in = current;
            current = safely("beforeRequest", () -> h.beforeRequest(in, ctx), in);
        }
        return current;
    }

    @Override public TurnVerdict afterAssistant(AssistantMessage message, TurnContext ctx) {
        for (AgentHooks h : hooks) {
            TurnVerdict v = safely("afterAssistant", () -> h.afterAssistant(message, ctx), TurnVerdict.PROCEED);
            if (!(v instanceof TurnVerdict.Proceed)) return v;
        }
        return TurnVerdict.PROCEED;
    }

    @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation cancel) {
        Json current = call.boundArguments();
        boolean rewritten = false;
        for (AgentHooks h : hooks) {
            BeforeToolCall in = new BeforeToolCall(call.assistantMessage(), call.toolCall(), call.rawArguments(), current, call.ctx());
            ToolDecision d = safely("beforeToolCall", () -> h.beforeToolCall(in, cancel), ToolDecision.ALLOW);
            switch (d) {
                case ToolDecision.Block b -> { return b; }
                case ToolDecision.Allow a -> {
                    if (a.rewritten() != null) { current = a.rewritten(); rewritten = true; }
                }
            }
        }
        return rewritten ? new ToolDecision.Allow(current) : ToolDecision.ALLOW;
    }

    @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation cancel) {
        ToolResult current = call.result();
        boolean overridden = false;
        for (AgentHooks h : hooks) {
            AfterToolCall in = new AfterToolCall(call.assistantMessage(), call.toolCall(), call.boundArguments(), current, current.isError(), call.ctx());
            Optional<ToolOverride> o = safely("afterToolCall", () -> h.afterToolCall(in, cancel), Optional.empty());
            if (o.isPresent()) { current = o.get().applyTo(current); overridden = true; }
        }
        if (!overridden) return Optional.empty();
        return Optional.of(new ToolOverride(Optional.of(current.content()), Optional.of(current.details()), Optional.of(current.isError())));
    }

    @Override public void onEvent(AgentEvent event) {
        for (AgentHooks h : hooks) safely("onEvent", () -> { h.onEvent(event); return null; }, null);
    }

    private <T> T safely(String name, Supplier<T> call, T fallback) {
        try {
            T result = call.get();
            return result == null && fallback != null ? fallback : result;
        } catch (Throwable t) {
            onHookError.accept(name, t);
            return fallback;
        }
    }
}
