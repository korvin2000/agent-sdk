package sdk.agent.turn;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ModelRef;
import sdk.agent.message.ToolResultMessage;

/// The whole state of one turn, as a value. `content`, `openBlocks` and `activeCalls` are the
/// streaming accumulator and are written only while `STREAMING`; `assistant` is materialised once
/// at the terminal stream event and is authoritative from `ASSISTANT_READY` onward. `slots` is
/// index-aligned with `assistant.toolCalls()`, initialized at assistant finalization and never resized.
///
/// `allowedTools` is captured from the final provider request. It is the execution authority for
/// this turn, independent of the host's broader tool registry.
public record TurnState(String runId,
                        int index,
                        TurnPhase phase,
                        ModelRef model,
                        Map<Integer, ContentBlock> content,
                        Map<Integer, OpenBlock> openBlocks,
                        Map<Integer, ArgAccumulator> activeCalls,
                        Set<String> allowedTools,
                        AssistantMessage assistant,
                        List<Optional<ToolResultMessage>> slots,
                        boolean stalled) {

    public TurnState {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(model, "model");
        content = immutableSorted(content);
        openBlocks = immutableSorted(openBlocks);
        activeCalls = immutableSorted(activeCalls);
        allowedTools = Set.copyOf(allowedTools);
        slots = List.copyOf(slots);
    }

    public static TurnState opening(String runId, int index, ModelRef model) {
        return new TurnState(runId, index, TurnPhase.OPENING, model, Map.of(), Map.of(),
                Map.of(), Set.of(), null, List.of(), false);
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

    private static <T> Map<Integer, T> immutableSorted(Map<Integer, T> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    Builder toBuilder() { return new Builder(this); }

    /// Package-private mutable copy for the machine's transitions; every exit goes through [#build].
    static final class Builder {
        String runId; int index; TurnPhase phase; ModelRef model;
        TreeMap<Integer, ContentBlock> content; TreeMap<Integer, OpenBlock> openBlocks;
        TreeMap<Integer, ArgAccumulator> activeCalls; Set<String> allowedTools;
        AssistantMessage assistant; List<Optional<ToolResultMessage>> slots; boolean stalled;

        private Builder(TurnState s) {
            runId = s.runId; index = s.index; phase = s.phase; model = s.model;
            content = new TreeMap<>(s.content); openBlocks = new TreeMap<>(s.openBlocks);
            activeCalls = new TreeMap<>(s.activeCalls); allowedTools = s.allowedTools;
            assistant = s.assistant; slots = new java.util.ArrayList<>(s.slots); stalled = s.stalled;
        }

        TurnState build() {
            return new TurnState(runId, index, phase, model, content, openBlocks, activeCalls, allowedTools, assistant, slots, stalled);
        }
    }
}
