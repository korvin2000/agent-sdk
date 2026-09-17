package sdk.agent.turn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ModelRef;
import sdk.agent.message.ToolResultMessage;

/// The whole state of one turn, as a value. `content`, `openBlock` and `activeCalls` are the
/// accumulator and are written only while `STREAMING`; `assistant` is materialised once, at the
/// terminal stream event, and from `ASSISTANT_READY` onward it is the only one read. `slots` is
/// index-aligned with `assistant.toolCalls()`, sized once at `Proceed` and never resized — which is
/// what makes "one result per call, in source order" hold by construction.
///
/// `preflight` records, per tool-call id, the model-facing reason the call's arguments were
/// unusable (unparseable, or cut off by a stall); such a call carries `Json.Null` arguments.
public record TurnState(String runId,
                        int index,
                        TurnPhase phase,
                        ModelRef model,
                        List<ContentBlock> content,
                        Optional<OpenBlock> openBlock,
                        SequencedMap<Integer, ArgAccumulator> activeCalls,
                        Map<String, String> preflight,
                        AssistantMessage assistant,
                        List<Optional<ToolResultMessage>> slots,
                        boolean stalled) {

    public TurnState {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(model, "model");
        content = List.copyOf(content);
        openBlock = Objects.requireNonNullElse(openBlock, Optional.empty());
        activeCalls = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(activeCalls));
        preflight = Map.copyOf(preflight);
        slots = List.copyOf(slots);
    }

    public static TurnState opening(String runId, int index, ModelRef model) {
        return new TurnState(runId, index, TurnPhase.OPENING, model, List.of(), Optional.empty(),
                new LinkedHashMap<>(), Map.of(), null, List.of(), false);
    }

    public List<ContentBlock.ToolCall> calls() { return assistant == null ? List.of() : assistant.toolCalls(); }

    /// Calls whose slot is still empty, in source order.
    public List<ContentBlock.ToolCall> pendingCalls() {
        var calls = calls();
        return java.util.stream.IntStream.range(0, calls.size())
                .filter(i -> slots.get(i).isEmpty())
                .mapToObj(calls::get)
                .toList();
    }

    public boolean allSettled() { return slots.stream().allMatch(Optional::isPresent); }

    public List<ToolResultMessage> results() { return slots.stream().flatMap(Optional::stream).toList(); }

    Builder toBuilder() { return new Builder(this); }

    /// Package-private mutable copy for the machine's transitions; every exit goes through [#build].
    static final class Builder {
        String runId; int index; TurnPhase phase; ModelRef model;
        List<ContentBlock> content; Optional<OpenBlock> openBlock;
        LinkedHashMap<Integer, ArgAccumulator> activeCalls; LinkedHashMap<String, String> preflight;
        AssistantMessage assistant; List<Optional<ToolResultMessage>> slots; boolean stalled;

        private Builder(TurnState s) {
            runId = s.runId; index = s.index; phase = s.phase; model = s.model;
            content = new java.util.ArrayList<>(s.content); openBlock = s.openBlock;
            activeCalls = new LinkedHashMap<>(s.activeCalls); preflight = new LinkedHashMap<>(s.preflight);
            assistant = s.assistant; slots = new java.util.ArrayList<>(s.slots); stalled = s.stalled;
        }

        TurnState build() {
            return new TurnState(runId, index, phase, model, content, openBlock, activeCalls, preflight, assistant, slots, stalled);
        }
    }
}
