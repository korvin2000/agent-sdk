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
/// `(events, next)`. No clock, no I/O, no threads, so every event invariant is assertable by feeding
/// hand-written inputs. The driver (`RunEngine`) owns the I/O and satisfies each [Need].
///
/// Three provider failure modes collapse to one path: an exception, a stream ending with no terminal
/// event, and a stall all produce an assistant message with `stopReason ∈ {ERROR, ABORTED}` or a
/// `stalled` flag. Nothing throws, nothing hangs. The partial message never enters a transcript:
/// only the final message is emitted, once, at `MessageEnd`.
public final class TurnMachine {

    public static final Duration DEFAULT_TOOL_IDLE_TIMEOUT = Duration.ofSeconds(180);

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
            case OPENING          -> in instanceof StepInput.Begin(var request)
                                        ? needs(s.toBuilder(), List.of(new AgentEvent.TurnStart(s.runId(), s.index(), now)),
                                                TurnPhase.STREAM_REQUESTED, new Need.Stream(request))
                                        : ignored(s, in);
            case STREAM_REQUESTED -> switch (in) {
                case StepInput.StreamOpened() -> {
                    var b = s.toBuilder();
                    b.phase = TurnPhase.STREAMING;
                    yield new StepOutcome.Needs(List.of(new AgentEvent.MessageStart(s.runId(), s.index(), now, partial(s, now))),
                            b.build(), chunkNeed(s));
                }
                case StepInput.StreamFailed(var message, var aborted) -> finish(s, aborted ? StopReason.ABORTED : StopReason.ERROR, message, false, Usage.EMPTY, null, now);
                default -> ignored(s, in);
            };
            case STREAMING        -> switch (in) {
                case StepInput.Chunk(var event)                        -> chunk(s, event, now);
                case StepInput.StreamExhausted()                       -> finish(s, StopReason.ERROR, "The provider stream ended without a terminal event.", false, Usage.EMPTY, null, now);
                case StepInput.StreamFailed(var message, var aborted)  -> finish(s, aborted ? StopReason.ABORTED : StopReason.ERROR, message, false, Usage.EMPTY, null, now);
                case StepInput.IdleTimedOut()                          -> finish(s, StopReason.TOOL_USE, null, true, Usage.EMPTY, null, now);
                default                                                -> ignored(s, in);
            };
            case ASSISTANT_READY  -> in instanceof StepInput.Verdict(var verdict) ? verdict(s, verdict, now) : ignored(s, in);
            case TOOLS_RUNNING    -> in instanceof StepInput.ToolSettled(var id, var result) ? settled(s, id, result, now) : ignored(s, in);
            case CLOSED           -> ignored(s, in);
        };
    }

    // ---- streaming -----------------------------------------------------------------------------

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
            case LlmStreamEvent.Done(var reason, var usage, var responseId) -> { return finish(s, reason, null, false, usage, responseId, now); }
            case LlmStreamEvent.Failed(var reason, var message)             -> { return finish(s, reason, message, false, Usage.EMPTY, null, now); }
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

    /// Whitespace-only text never opens a block; an empty thinking block
    /// survives only if it carries a signature or is redacted (required for redacted-reasoning round trips).
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

    private StepOutcome finish(TurnState s, StopReason reason, String errorMessage, boolean stalled,
                               Usage usage, String responseId, Instant now) {
        var b = s.toBuilder();
        closeOpen(b);
        flushActiveCalls(b, stalled);
        b.stalled = stalled;
        b.assistant = new AssistantMessage(b.content, s.model(), responseId, usage, reason, errorMessage, now);
        b.phase = TurnPhase.ASSISTANT_READY;
        TurnState next = b.build();
        // A turn that dies before its stream opened still owes TurnStart/MessageStart (I3, I8).
        var events = new ArrayList<AgentEvent>(3);
        if (s.phase() == TurnPhase.OPENING) events.add(new AgentEvent.TurnStart(s.runId(), s.index(), now));
        if (s.phase() != TurnPhase.STREAMING) events.add(new AgentEvent.MessageStart(s.runId(), s.index(), now, next.assistant()));
        events.add(new AgentEvent.MessageEnd(s.runId(), s.index(), now, next.assistant()));
        return new StepOutcome.Needs(events, next, new Need.Verdict(next.assistant()));
    }

    /// The stale-snapshot rule: on a parse failure fall back to the
    /// start-of-call snapshot **only if the stream did not stall** — after a stall the snapshot
    /// is stale and the fragments are truncated, so the call is marked unusable instead.
    private static void flushActiveCalls(TurnState.Builder b, boolean stalled) {
        for (ArgAccumulator acc : b.activeCalls.values()) {
            String fragments = acc.fragments().strip();
            Json arguments;
            if (fragments.isEmpty()) {
                arguments = acc.initialArguments() instanceof Json.Obj o ? o : Json.Obj.EMPTY;
            } else {
                try {
                    arguments = Json.parse(fragments);
                } catch (JsonParseException _) {
                    if (stalled) {
                        arguments = Json.Null.NULL;
                        b.preflight.put(acc.toolCallId(), ToolMessages.ARGS_CUT_OFF_BY_STALL);
                    } else if (acc.initialArguments() instanceof Json.Obj o && !o.isEmpty()) {
                        arguments = o;
                    } else {
                        arguments = Json.Null.NULL;
                        b.preflight.put(acc.toolCallId(), ToolMessages.ARGS_INVALID_JSON);
                    }
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

    private StepOutcome verdict(TurnState s, TurnVerdict verdict, Instant now) {
        return switch (verdict) {
            case TurnVerdict.Stop _, TurnVerdict.Retry _ -> close(s.toBuilder(), List.of(), now);
            case TurnVerdict.Proceed _ -> {
                List<ContentBlock.ToolCall> calls = s.calls();
                if (calls.isEmpty()) yield close(s.toBuilder(), List.of(), now);
                var b = s.toBuilder();
                b.slots = new ArrayList<>(Collections.nCopies(calls.size(), Optional.empty()));
                b.phase = TurnPhase.TOOLS_RUNNING;
                TurnState next = b.build();
                yield new StepOutcome.Needs(List.of(), next, new Need.Tools(next.pendingCalls()));
            }
        };
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
        if (next.allSettled()) return close(b, List.of(), now);
        return new StepOutcome.Needs(List.of(), next, new Need.Tools(next.pendingCalls()));
    }

    private StepOutcome cancel(TurnState s, Instant now) {
        if (s.assistant() == null) {
            return finish(s, StopReason.ABORTED, "The run was aborted.", false, Usage.EMPTY, null, now);
        }
        var b = s.toBuilder();
        var padded = new ArrayList<AgentEvent>();
        List<ContentBlock.ToolCall> calls = s.calls();
        for (int i = 0; i < b.slots.size(); i++) {
            if (b.slots.get(i).isPresent()) continue;
            var call = calls.get(i);
            var pad = new ToolResultMessage(call.id(), call.name(), List.of(ContentBlock.Text.of(ToolMessages.NOT_EXECUTED)),
                    Json.Null.NULL, true, now);
            b.slots.set(i, Optional.of(pad));
            padded.add(new AgentEvent.MessageStart(s.runId(), s.index(), now, pad));
            padded.add(new AgentEvent.MessageEnd(s.runId(), s.index(), now, pad));
        }
        return close(b, padded, now);
    }

    /// The single exit: `TurnEnd` carries exactly the index-aligned results.
    private static StepOutcome close(TurnState.Builder b, List<AgentEvent> before, Instant now) {
        b.phase = TurnPhase.CLOSED;
        TurnState next = b.build();
        var events = new ArrayList<>(before);
        events.add(new AgentEvent.TurnEnd(next.runId(), next.index(), now, next.assistant(), next.results()));
        return new StepOutcome.Finished(events, next);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static StepOutcome needs(TurnState.Builder b, List<AgentEvent> events, TurnPhase phase, Need need) {
        b.phase = phase;
        return new StepOutcome.Needs(events, b.build(), need);
    }

    private static StepOutcome ignored(TurnState s, StepInput in) {
        return new StepOutcome.Ignored(in.getClass().getSimpleName() + " is not valid in phase " + s.phase(), s);
    }
}
