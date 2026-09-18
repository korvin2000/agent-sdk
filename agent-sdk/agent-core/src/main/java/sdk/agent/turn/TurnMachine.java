package sdk.agent.turn;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import sdk.agent.event.AgentEvent;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.json.Json;
import sdk.agent.json.JsonParseException;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ToolMessages;
import sdk.agent.tool.ToolSpec;

/// The turn as a pure state machine: total and deterministic — same `(state, input, now)` → same
/// `(events, next)`. No clock, no I/O, no threads, so every event invariant is assertable by feeding
/// hand-written inputs. The driver (`RunEngine`) owns the I/O and satisfies each [Need].
///
/// Every provider failure — an exception, a stream ending with no terminal event, a stall while a
/// tool call is being assembled — becomes an assistant message with `stopReason ∈ {ERROR, ABORTED}`.
/// Nothing throws, nothing hangs. The partial message never enters a transcript: only the final
/// message is emitted, once, at `MessageEnd`. Every turn leaves through [#close], which pads the
/// calls that never ran, so `TurnEnd` and the transcript carry one result per call however the
/// turn ended — a provider rejects an assistant tool call with no matching result.
public final class TurnMachine {

    public static final Duration DEFAULT_TOOL_IDLE_TIMEOUT = Duration.ofSeconds(180);

    public static final String STALLED = "The provider stream stalled while assembling a tool call.";

    private final OptionalLong idleTimeoutMillis;

    public TurnMachine(Duration toolIdleTimeout) {
        this.idleTimeoutMillis = toolIdleTimeout == null || toolIdleTimeout.isZero() || toolIdleTimeout.isNegative()
                ? OptionalLong.empty()
                : OptionalLong.of(toolIdleTimeout.toMillis());
    }

    public static TurnMachine standard() { return new TurnMachine(DEFAULT_TOOL_IDLE_TIMEOUT); }

    public StepOutcome step(TurnState s, StepInput in, Instant now) {
        if (in instanceof StepInput.Cancel && s.phase() != TurnPhase.CLOSED) return cancel(s, now);
        return switch (s.phase()) {
            case OPENING          -> in instanceof StepInput.Begin(var request) ? begin(s, request, now) : ignored(s, in);
            case STREAM_REQUESTED -> switch (in) {
                case StepInput.StreamOpened() -> {
                    var b = s.toBuilder();
                    b.phase = TurnPhase.STREAMING;
                    yield new StepOutcome.Needs(List.of(new AgentEvent.MessageStart(s.runId(), s.index(), now, partial(s, now))),
                            b.build(), chunkNeed(s));
                }
                case StepInput.StreamFailed(var message, var aborted) -> finish(s, aborted ? StopReason.ABORTED : StopReason.ERROR, message, Usage.EMPTY, null, now);
                default -> ignored(s, in);
            };
            case STREAMING        -> switch (in) {
                case StepInput.Chunk(var event)                        -> chunk(s, event, now);
                case StepInput.StreamExhausted()                       -> finish(s, StopReason.ERROR, "The provider stream ended without a terminal event.", Usage.EMPTY, null, now);
                case StepInput.StreamFailed(var message, var aborted)  -> finish(s, aborted ? StopReason.ABORTED : StopReason.ERROR, message, Usage.EMPTY, null, now);
                case StepInput.IdleTimedOut()                          -> finish(s, StopReason.ERROR, STALLED, Usage.EMPTY, null, now);
                default                                                -> ignored(s, in);
            };
            case ASSISTANT_READY  -> in instanceof StepInput.Verdict(var verdict) ? verdict(s, verdict, now) : ignored(s, in);
            case TOOLS_RUNNING    -> in instanceof StepInput.ToolSettled(var id, var result) ? settled(s, id, result, now) : ignored(s, in);
            case CLOSED           -> ignored(s, in);
        };
    }

    // ---- streaming -----------------------------------------------------------------------------

    /// The advertised tools are captured here: execution authority for the turn is what the model was offered.
    private static StepOutcome begin(TurnState s, LlmRequest request, Instant now) {
        var b = s.toBuilder();
        b.allowedTools = request.tools().stream().map(ToolSpec::name).collect(Collectors.toSet());
        b.phase = TurnPhase.STREAM_REQUESTED;
        return new StepOutcome.Needs(List.of(new AgentEvent.TurnStart(s.runId(), s.index(), now)), b.build(), new Need.Stream(request));
    }

    private StepOutcome chunk(TurnState s, LlmStreamEvent e, Instant now) {
        var b = s.toBuilder();
        switch (e) {
            case LlmStreamEvent.Start _ -> { return new StepOutcome.Needs(List.of(), s, chunkNeed(s)); }
            case LlmStreamEvent.TextStart(var index)                    -> { closeOpen(b); b.openBlock = Optional.of(OpenBlock.text(index)); }
            case LlmStreamEvent.ThinkingStart(var index)                -> { closeOpen(b); b.openBlock = Optional.of(OpenBlock.thinking(index)); }
            case LlmStreamEvent.TextDelta(var index, var delta)         -> b.openBlock = Optional.of(open(b, OpenBlock.Kind.TEXT, index).append(delta));
            case LlmStreamEvent.ThinkingDelta(var index, var delta)     -> b.openBlock = Optional.of(open(b, OpenBlock.Kind.THINKING, index).append(delta));
            case LlmStreamEvent.TextEnd(var index, var text, var sig)   -> { b.openBlock = Optional.of(open(b, OpenBlock.Kind.TEXT, index).finish(text, sig, false)); closeOpen(b); }
            case LlmStreamEvent.ThinkingEnd(var index, var text, var sig, var redacted) -> { b.openBlock = Optional.of(open(b, OpenBlock.Kind.THINKING, index).finish(text, sig, redacted)); closeOpen(b); }
            case LlmStreamEvent.ToolCallStart(var index, var id, var name, var initial) -> {
                closeOpen(b);
                b.activeCalls.put(index, new ArgAccumulator(id, name, "", initial));
            }
            case LlmStreamEvent.ToolCallDelta(var index, var fragment, var replace) -> {
                ArgAccumulator acc = b.activeCalls.get(index);
                if (acc == null) return new StepOutcome.Ignored("ToolCallDelta for unknown content index " + index, s);
                b.activeCalls.put(index, replace ? acc.replaced(fragment) : acc.append(fragment));
            }
            case LlmStreamEvent.ToolCallEnd(var index, var call) -> {
                closeOpen(b);
                b.activeCalls.remove(index);
                b.content.add(call);
            }
            case LlmStreamEvent.Done(var reason, var usage, var responseId) -> { return finish(s, reason, null, usage, responseId, now); }
            case LlmStreamEvent.Failed(var reason, var message)             -> { return finish(s, reason, message, Usage.EMPTY, null, now); }
        }
        TurnState next = b.build();
        var update = new AgentEvent.MessageUpdate(s.runId(), s.index(), now, partial(next, now), e);
        return new StepOutcome.Needs(List.of(update), next, chunkNeed(next));
    }

    private Need chunkNeed(TurnState s) {
        return new Need.Chunk(s.activeCalls().isEmpty() ? OptionalLong.empty() : idleTimeoutMillis);
    }

    /// The open block of this kind and index, opening one implicitly if a delta arrives first.
    private static OpenBlock open(TurnState.Builder b, OpenBlock.Kind kind, int index) {
        OpenBlock current = b.openBlock.orElse(null);
        if (current != null && current.kind() == kind) return current;
        closeOpen(b);
        return kind == OpenBlock.Kind.TEXT ? OpenBlock.text(index) : OpenBlock.thinking(index);
    }

    /// Whitespace-only text never opens a block (a provider rejects it on the way back); an empty
    /// thinking block survives only if it carries a signature or is redacted (redacted-reasoning round trips).
    private static void closeOpen(TurnState.Builder b) {
        b.openBlock.ifPresent(open -> {
            boolean keep = switch (open.kind()) {
                case TEXT     -> !open.text().isBlank();
                case THINKING -> !open.text().isBlank() || open.signature() != null || open.redacted();
            };
            if (keep) b.content.add(open.close());
        });
        b.openBlock = Optional.empty();
    }

    /// Materialises the assistant message and sizes one result slot per call.
    private static StepOutcome finish(TurnState s, StopReason reason, String errorMessage, Usage usage, String responseId, Instant now) {
        var b = s.toBuilder();
        closeOpen(b);
        flushActiveCalls(b);
        b.assistant = new AssistantMessage(b.content, s.model(), responseId, usage, reason, errorMessage, now);
        b.content.clear();
        b.slots = new ArrayList<>(Collections.nCopies(b.assistant.toolCalls().size(), Optional.empty()));
        b.phase = TurnPhase.ASSISTANT_READY;
        TurnState next = b.build();
        // A turn that dies before its stream opened still owes TurnStart/MessageStart (I3, I8).
        var events = new ArrayList<AgentEvent>(3);
        if (s.phase() == TurnPhase.OPENING) events.add(new AgentEvent.TurnStart(s.runId(), s.index(), now));
        if (s.phase() != TurnPhase.STREAMING) events.add(new AgentEvent.MessageStart(s.runId(), s.index(), now, next.assistant()));
        events.add(new AgentEvent.MessageEnd(s.runId(), s.index(), now, next.assistant()));
        return new StepOutcome.Needs(events, next, new Need.Verdict(next.assistant()));
    }

    /// Received fragments are authoritative: unparseable arguments become `Json.Null`, which the
    /// funnel refuses with [ToolMessages#ARGS_INVALID_JSON]. The start-of-call snapshot is used only
    /// when no fragment arrived at all (a provider that sends the arguments whole).
    private static void flushActiveCalls(TurnState.Builder b) {
        for (ArgAccumulator acc : b.activeCalls.values()) {
            String fragments = acc.fragments().strip();
            Json arguments;
            if (fragments.isEmpty()) {
                arguments = acc.initialArguments() instanceof Json.Obj o ? o : Json.Obj.EMPTY;
            } else {
                try {
                    arguments = Json.parse(fragments);
                } catch (JsonParseException _) {
                    arguments = Json.Null.NULL;
                }
            }
            b.content.add(new ContentBlock.ToolCall(acc.toolCallId(), acc.toolName(), arguments, null));
        }
        b.activeCalls.clear();
    }

    private static AssistantMessage partial(TurnState s, Instant now) {
        List<ContentBlock> blocks = s.openBlock()
                .map(open -> { var l = new ArrayList<>(s.content()); l.add(open.close()); return (List<ContentBlock>) l; })
                .orElse(s.content());
        return new AssistantMessage(blocks, s.model(), null, Usage.EMPTY, StopReason.STOP, null, now);
    }

    // ---- verdict and tools ---------------------------------------------------------------------

    /// Only `Proceed` on a healthy turn with calls runs anything; `Stop`, `Retry`, a failed turn
    /// and a turn without calls all close, padding whatever the model asked for.
    private StepOutcome verdict(TurnState s, TurnVerdict verdict, Instant now) {
        if (!(verdict instanceof TurnVerdict.Proceed) || s.assistant().terminal() || s.calls().isEmpty()) {
            return close(s.toBuilder(), now);
        }
        var b = s.toBuilder();
        b.phase = TurnPhase.TOOLS_RUNNING;
        TurnState next = b.build();
        return new StepOutcome.Needs(List.of(), next, new Need.Tools(next.pendingCalls()));
    }

    private StepOutcome settled(TurnState s, String toolCallId, ToolResultMessage result, Instant now) {
        List<ContentBlock.ToolCall> calls = s.calls();
        int index = -1;
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).id().equals(toolCallId)) { index = i; break; }
        if (index < 0) return new StepOutcome.Ignored("ToolSettled for unknown tool call id " + toolCallId, s);
        if (s.slots().get(index).isPresent()) return new StepOutcome.Ignored("ToolSettled twice for " + toolCallId, s);
        var b = s.toBuilder();
        b.slots.set(index, Optional.of(result));
        TurnState next = b.build();
        if (next.allSettled()) return close(b, now);
        return new StepOutcome.Needs(List.of(), next, new Need.Tools(next.pendingCalls()));
    }

    /// Abort, or "finish this turn without running anything else": a turn with no final message
    /// yet gets an `ABORTED` one, then the turn closes with every open slot padded.
    private StepOutcome cancel(TurnState s, Instant now) {
        if (s.assistant() != null) return close(s.toBuilder(), now);
        StepOutcome ready = finish(s, StopReason.ABORTED, "The run was aborted.", Usage.EMPTY, null, now);
        StepOutcome closed = close(ready.state().toBuilder(), now);
        var events = new ArrayList<>(ready.events());
        events.addAll(closed.events());
        return new StepOutcome.Finished(events, closed.state());
    }

    /// The single exit: unfilled slots are padded with `action was not executed`, then `TurnEnd`
    /// carries exactly the index-aligned results.
    private static StepOutcome close(TurnState.Builder b, Instant now) {
        var events = new ArrayList<AgentEvent>();
        List<ContentBlock.ToolCall> calls = b.assistant == null ? List.of() : b.assistant.toolCalls();
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

    private static StepOutcome ignored(TurnState s, StepInput in) {
        return new StepOutcome.Ignored(in.getClass().getSimpleName() + " is not valid in phase " + s.phase(), s);
    }
}
