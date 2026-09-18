package sdk.agent.turn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;

import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ModelRef;
import sdk.agent.message.ToolResultMessage;

/// The whole state of one turn, as a value. `content`, `openBlock` and `activeCalls` are the
/// streaming accumulator, written only while `STREAMING` and empty once `assistant` is
/// materialised at the terminal stream event. `slots` is index-aligned with
/// `assistant.toolCalls()`, sized when the assistant is final and never resized — which is what
/// makes "one result per call, in source order" hold by construction.
///
/// `allowedTools` is the set of tool names the request actually advertised: a `beforeRequest`
/// hook that strips tools also strips the authority to run them, whatever the registry holds.
public record TurnState(String runId,
                        int index,
                        TurnPhase phase,
                        ModelRef model,
                        List<ContentBlock> content,
                        Optional<OpenBlock> openBlock,
                        SequencedMap<Integer, ArgAccumulator> activeCalls,
                        Set<String> allowedTools,
                        AssistantMessage assistant,
                        List<Optional<ToolResultMessage>> slots) {

    public TurnState {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(model, "model");
        content = List.copyOf(content);
        openBlock = Objects.requireNonNullElse(openBlock, Optional.empty());
        activeCalls = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(activeCalls));
        allowedTools = Set.copyOf(allowedTools);
        slots = List.copyOf(slots);
        int calls = assistant == null ? 0 : assistant.toolCalls().size();
        if (slots.size() != calls) throw new IllegalArgumentException("slots (" + slots.size() + ") must align with tool calls (" + calls + ")");
    }

    public static TurnState opening(String runId, int index, ModelRef model) {
        return new TurnState(runId, index, TurnPhase.OPENING, model, List.of(), Optional.empty(),
                new LinkedHashMap<>(), Set.of(), null, List.of());
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
        LinkedHashMap<Integer, ArgAccumulator> activeCalls; Set<String> allowedTools;
        AssistantMessage assistant; List<Optional<ToolResultMessage>> slots;

        private Builder(TurnState s) {
            runId = s.runId; index = s.index; phase = s.phase; model = s.model;
            content = new java.util.ArrayList<>(s.content); openBlock = s.openBlock;
            activeCalls = new LinkedHashMap<>(s.activeCalls); allowedTools = s.allowedTools;
            assistant = s.assistant; slots = new java.util.ArrayList<>(s.slots);
        }

        TurnState build() {
            return new TurnState(runId, index, phase, model, content, openBlock, activeCalls, allowedTools, assistant, slots);
        }
    }
}
