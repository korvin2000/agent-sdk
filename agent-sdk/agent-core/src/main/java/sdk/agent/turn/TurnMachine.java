package sdk.agent.turn;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import sdk.agent.event.AgentEvent;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.json.Json;
import sdk.agent.json.JsonParseException;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ToolMessages;

/// The turn as a pure state machine: total and deterministic — same `(state, input, now)` → same
/// `(events, next)`. No clock, I/O, or threads are used here; the driver owns every [Need].
public final class TurnMachine {

    public static final Duration DEFAULT_TOOL_IDLE_TIMEOUT = Duration.ofSeconds(180);

    private final OptionalLong idleTimeoutMillis;

    public TurnMachine(Duration toolIdleTimeout) {
        idleTimeoutMillis = toolIdleTimeout == null || toolIdleTimeout.isZero() || toolIdleTimeout.isNegative()
                ? OptionalLong.empty() : OptionalLong.of(toolIdleTimeout.toMillis());
    }

    public static TurnMachine standard() { return new TurnMachine(DEFAULT_TOOL_IDLE_TIMEOUT); }

    public StepOutcome step(TurnState s, StepInput in, Instant now) {
        if (in instanceof StepInput.Cancel && s.phase() != TurnPhase.CLOSED) return cancel(s, now);
        return switch (s.phase()) {
            case OPENING -> in instanceof StepInput.Begin(var request) ? begin(s, request, now) : ignored(s, in);
            case STREAM_REQUESTED -> switch (in) {
                case StepInput.StreamOpened() -> {
                    var b = s.toBuilder(); b.phase = TurnPhase.STREAMING;
                    yield new StepOutcome.Needs(
                            List.of(new AgentEvent.MessageStart(s.runId(), s.index(), now, partial(s, now))),
                            b.build(), chunkNeed(s));
                }
                case StepInput.StreamFailed(var message, var aborted) -> finish(s,
                        aborted ? StopReason.ABORTED : StopReason.ERROR, message, false, false, Usage.EMPTY, null,
                        Json.Null.NULL, now);
                default -> ignored(s, in);
            };
            case STREAMING -> switch (in) {
                case StepInput.Chunk(var event) -> chunk(s, event, now);
                case StepInput.StreamExhausted() -> finish(s, StopReason.ERROR,
                        "The provider stream ended without a terminal event.", false, false, Usage.EMPTY, null,
                        Json.Null.NULL, now);
                case StepInput.StreamFailed(var message, var aborted) -> finish(s,
                        aborted ? StopReason.ABORTED : StopReason.ERROR, message, false, false, Usage.EMPTY, null,
                        Json.Null.NULL, now);
                case StepInput.IdleTimedOut() -> finish(s, StopReason.ERROR,
                        "The provider stream stalled while assembling a tool call.", true, false, Usage.EMPTY, null,
                        Json.Null.NULL, now);
                default -> ignored(s, in);
            };
            case ASSISTANT_READY -> in instanceof StepInput.Verdict(var verdict) ? verdict(s, verdict, now) : ignored(s, in);
            case TOOLS_RUNNING -> in instanceof StepInput.ToolSettled(var id, var result) ? settled(s, id, result, now) : ignored(s, in);
            case CLOSED -> ignored(s, in);
        };
    }

    private static StepOutcome begin(TurnState s, sdk.agent.provider.LlmRequest request, Instant now) {
        var b = s.toBuilder();
        b.allowedTools = request.tools().stream().map(sdk.agent.tool.ToolSpec::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return needs(b, List.of(new AgentEvent.TurnStart(s.runId(), s.index(), now)), TurnPhase.STREAM_REQUESTED,
                new Need.Stream(request));
    }

    private StepOutcome chunk(TurnState s, LlmStreamEvent event, Instant now) {
        var b = s.toBuilder();
        switch (event) {
            case LlmStreamEvent.Start _ -> { return new StepOutcome.Needs(List.of(), s, chunkNeed(s)); }
            case LlmStreamEvent.TextStart(var index) -> {
                if (badIndex(index) || occupied(b, index)) return protocolError(s, b, "TextStart occupies content index " + index, now);
                b.openBlocks.put(index, OpenBlock.text(index));
            }
            case LlmStreamEvent.ThinkingStart(var index) -> {
                if (badIndex(index) || occupied(b, index)) return protocolError(s, b, "ThinkingStart occupies content index " + index, now);
                b.openBlocks.put(index, OpenBlock.thinking(index));
            }
            case LlmStreamEvent.TextDelta(var index, var delta) -> {
                if (delta == null) return protocolError(s, b, "TextDelta has null text at index " + index, now);
                var open = open(b, OpenBlock.Kind.TEXT, index);
                if (open == null) return protocolError(s, b, "TextDelta conflicts at content index " + index, now);
                b.openBlocks.put(index, open.append(delta));
            }
            case LlmStreamEvent.ThinkingDelta(var index, var delta) -> {
                if (delta == null) return protocolError(s, b, "ThinkingDelta has null text at index " + index, now);
                var open = open(b, OpenBlock.Kind.THINKING, index);
                if (open == null) return protocolError(s, b, "ThinkingDelta conflicts at content index " + index, now);
                b.openBlocks.put(index, open.append(delta));
            }
            case LlmStreamEvent.TextEnd(var index, var text, var signature) -> {
                var open = endOpen(b, OpenBlock.Kind.TEXT, index);
                if (open == null) return protocolError(s, b, "TextEnd has no matching start at index " + index, now);
                b.openBlocks.remove(index); b.content.put(index, open.finish(text, signature, false).close());
            }
            case LlmStreamEvent.ThinkingEnd(var index, var text, var signature, var redacted) -> {
                var open = endOpen(b, OpenBlock.Kind.THINKING, index);
                if (open == null) return protocolError(s, b, "ThinkingEnd has no matching start at index " + index, now);
                b.openBlocks.remove(index); b.content.put(index, open.finish(text, signature, redacted).close());
            }
            case LlmStreamEvent.ToolCallStart(var index, var id, var name, var initial) -> {
                if (badIndex(index) || occupied(b, index) || blank(id) || blank(name) || callIdKnown(b, id))
                    return protocolError(s, b, "Invalid ToolCallStart at content index " + index, now);
                b.activeCalls.put(index, new ArgAccumulator(id, name, null, initial));
            }
            case LlmStreamEvent.ToolCallDelta(var index, var fragment, var replace) -> {
                var acc = b.activeCalls.get(index);
                if (acc == null) return protocolError(s, b, "ToolCallDelta has no matching start at index " + index, now);
                b.activeCalls.put(index, replace ? acc.replaced(fragment) : acc.append(fragment));
            }
            case LlmStreamEvent.ToolCallEnd(var index, var call) -> {
                var acc = b.activeCalls.get(index);
                if (acc == null || call == null || !acc.toolCallId().equals(call.id()) || !acc.toolName().equals(call.name())
                        || blank(call.id()) || callIdKnownExcept(b, call.id(), index))
                    return protocolError(s, b, "ToolCallEnd does not match index " + index, now);
                b.activeCalls.remove(index); b.content.put(index, call);
            }
            case LlmStreamEvent.Done(var reason, var usage, var responseId, var providerData) ->
                    { return finish(b.build(), reason, null, false, reason != StopReason.ERROR && reason != StopReason.ABORTED,
                            usage, responseId, providerData, now); }
            case LlmStreamEvent.Failed(var reason, var message) ->
                    { return finish(b.build(), reason, message, false, false, Usage.EMPTY, null, Json.Null.NULL, now); }
        }
        TurnState next = b.build();
        return new StepOutcome.Needs(List.of(new AgentEvent.MessageUpdate(s.runId(), s.index(), now, partial(next, now), event)),
                next, chunkNeed(next));
    }

    private static boolean badIndex(int index) { return index < 0; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean occupied(TurnState.Builder b, int index) {
        return b.content.containsKey(index) || b.openBlocks.containsKey(index) || b.activeCalls.containsKey(index);
    }

    private static boolean callIdKnown(TurnState.Builder b, String id) {
        return b.activeCalls.values().stream().anyMatch(a -> a.toolCallId().equals(id))
                || b.content.values().stream().filter(ContentBlock.ToolCall.class::isInstance)
                .map(ContentBlock.ToolCall.class::cast).anyMatch(c -> c.id().equals(id));
    }

    private static boolean callIdKnownExcept(TurnState.Builder b, String id, int index) {
        return b.activeCalls.entrySet().stream().anyMatch(e -> e.getKey() != index && e.getValue().toolCallId().equals(id))
                || b.content.values().stream().filter(ContentBlock.ToolCall.class::isInstance)
                .map(ContentBlock.ToolCall.class::cast).anyMatch(c -> c.id().equals(id));
    }

    /// Returns the exact indexed block, implicitly opening a previously unseen delta index.
    private static OpenBlock open(TurnState.Builder b, OpenBlock.Kind kind, int index) {
        if (badIndex(index) || b.content.containsKey(index) || b.activeCalls.containsKey(index)) return null;
        var current = b.openBlocks.get(index);
        if (current != null && current.kind() != kind) return null;
        return current != null ? current : kind == OpenBlock.Kind.TEXT ? OpenBlock.text(index) : OpenBlock.thinking(index);
    }

    private static OpenBlock endOpen(TurnState.Builder b, OpenBlock.Kind kind, int index) {
        if (badIndex(index) || b.content.containsKey(index) || b.activeCalls.containsKey(index)) return null;
        var current = b.openBlocks.get(index);
        return current != null && current.kind() == kind ? current : null;
    }

    private Need chunkNeed(TurnState s) {
        return new Need.Chunk(s.activeCalls().isEmpty() ? OptionalLong.empty() : idleTimeoutMillis);
    }

    private static StepOutcome finish(TurnState s, StopReason reason, String errorMessage, boolean stalled,
                                      boolean allowInitial, Usage usage, String responseId, Json providerData, Instant now) {
        var b = s.toBuilder();
        flushOpen(b);
        flushActiveCalls(b, allowInitial);
        b.stalled = stalled;
        var replay = providerData;
        b.assistant = new AssistantMessage(new ArrayList<>(b.content.values()), s.model(), responseId, usage, reason,
                errorMessage, replay, now);
        b.content.clear();
        b.openBlocks.clear();
        b.activeCalls.clear();
        b.slots = new ArrayList<>(Collections.nCopies(b.assistant.toolCalls().size(), Optional.<ToolResultMessage>empty()));
        b.phase = TurnPhase.ASSISTANT_READY;
        TurnState next = b.build();
        var events = new ArrayList<AgentEvent>(3);
        if (s.phase() == TurnPhase.OPENING) events.add(new AgentEvent.TurnStart(s.runId(), s.index(), now));
        if (s.phase() != TurnPhase.STREAMING) events.add(new AgentEvent.MessageStart(s.runId(), s.index(), now, next.assistant()));
        events.add(new AgentEvent.MessageEnd(s.runId(), s.index(), now, next.assistant()));
        return new StepOutcome.Needs(events, next, new Need.Verdict(next.assistant()));

    }
    private static void flushOpen(TurnState.Builder b) {
        b.openBlocks.forEach((index, open) -> b.content.put(index, open.close()));
        b.openBlocks.clear();
    }

    /// A call with no fragments may use its initial object only on a successful terminal protocol.
    private static void flushActiveCalls(TurnState.Builder b, boolean allowInitial) {
        for (var entry : b.activeCalls.entrySet()) {
            var acc = entry.getValue();
            var fragments = acc.fragments();
            Json arguments;
            if (fragments == null && allowInitial) arguments = acc.initialArguments() instanceof Json.Obj o ? o : Json.Null.NULL;
            else if (fragments == null) arguments = Json.Null.NULL;
            else {
                try { arguments = Json.parse(fragments); }
                catch (JsonParseException _) { arguments = Json.Null.NULL; }
            }
            b.content.put(entry.getKey(), new ContentBlock.ToolCall(acc.toolCallId(), acc.toolName(), arguments, null));
        }
        b.activeCalls.clear();
    }

    private static AssistantMessage partial(TurnState s, Instant now) {
        var blocks = new java.util.TreeMap<Integer, ContentBlock>(s.content());
        s.openBlocks().forEach((index, open) -> blocks.put(index, open.close()));
        return new AssistantMessage(new ArrayList<>(blocks.values()), s.model(), null, Usage.EMPTY, StopReason.STOP,
                null, Json.Null.NULL, now);
    }

    private StepOutcome protocolError(TurnState original, TurnState.Builder b, String detail, Instant now) {
        return finish(b.build(), StopReason.ERROR, "Provider protocol error: " + detail, false, false, Usage.EMPTY, null,
                Json.Null.NULL, now);
    }

    private StepOutcome verdict(TurnState s, TurnVerdict verdict, Instant now) {
        return switch (verdict) {
            case TurnVerdict.Stop _, TurnVerdict.Retry _ -> close(s.toBuilder(), List.of(), now);
            case TurnVerdict.Proceed _ -> {
                List<ContentBlock.ToolCall> calls = s.calls();
                if (s.assistant().stopReason() == StopReason.ERROR || s.assistant().stopReason() == StopReason.ABORTED || calls.isEmpty())
                    yield close(s.toBuilder(), List.of(), now);
                var b = s.toBuilder(); b.phase = TurnPhase.TOOLS_RUNNING;
                yield new StepOutcome.Needs(List.of(), b.build(), new Need.Tools(b.build().pendingCalls()));
            }
        };
    }

    private StepOutcome settled(TurnState s, String toolCallId, ToolResultMessage result, Instant now) {
        List<ContentBlock.ToolCall> calls = s.calls();
        int index = -1;
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).id().equals(toolCallId)) { index = i; break; }
        if (index < 0) return new StepOutcome.Ignored("ToolSettled for unknown tool call id " + toolCallId, s);
        if (s.slots().get(index).isPresent()) return new StepOutcome.Ignored("ToolSettled twice for " + toolCallId, s);
        var b = s.toBuilder(); b.slots.set(index, java.util.Optional.of(result));
        TurnState next = b.build();
        return next.allSettled() ? close(b, List.of(), now) : new StepOutcome.Needs(List.of(), next, new Need.Tools(next.pendingCalls()));
    }

    private StepOutcome cancel(TurnState s, Instant now) {
        if (s.assistant() == null) {
            StepOutcome ready = finish(s, StopReason.ABORTED, "The run was aborted.", false, false, Usage.EMPTY, null, Json.Null.NULL, now);
            StepOutcome closed = close(ready.state().toBuilder(), List.of(), now);
            var events = new ArrayList<>(ready.events()); events.addAll(closed.events());
            return new StepOutcome.Finished(events, closed.state());
        }
        return close(s.toBuilder(), List.of(), now);
    }

    private static StepOutcome close(TurnState.Builder b, List<AgentEvent> before, Instant now) {
        List<ContentBlock.ToolCall> calls = b.assistant == null ? List.of() : b.assistant.toolCalls();
        if (b.slots.size() < calls.size()) b.slots = new ArrayList<>(Collections.nCopies(calls.size(), Optional.<ToolResultMessage>empty()));
        var events = new ArrayList<>(before);
        for (int i = 0; i < calls.size(); i++) {
            if (b.slots.get(i).isPresent()) continue;
            var call = calls.get(i);
            var pad = new ToolResultMessage(call.id(), call.name(), List.of(ContentBlock.Text.of(ToolMessages.NOT_EXECUTED)),
                    Json.Null.NULL, true, now);
            b.slots.set(i, Optional.of(pad));
            events.add(new AgentEvent.MessageStart(b.runId, b.index, now, pad));
            events.add(new AgentEvent.MessageEnd(b.runId, b.index, now, pad));
        }
        b.phase = TurnPhase.CLOSED;
        TurnState next = b.build();
        events.add(new AgentEvent.TurnEnd(next.runId(), next.index(), now, next.assistant(), next.results()));
        return new StepOutcome.Finished(events, next);
    }

    private static StepOutcome needs(TurnState.Builder b, List<AgentEvent> events, TurnPhase phase, Need need) {
        b.phase = phase; return new StepOutcome.Needs(events, b.build(), need);
    }

    private static StepOutcome ignored(TurnState s, StepInput in) {
        return new StepOutcome.Ignored(in.getClass().getSimpleName() + " is not valid in phase " + s.phase(), s);
    }
}
