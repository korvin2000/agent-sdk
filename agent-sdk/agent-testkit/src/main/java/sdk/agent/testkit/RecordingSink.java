package sdk.agent.testkit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import sdk.agent.event.AgentEvent;
import sdk.agent.event.AgentListener;
import sdk.agent.event.EventSink;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ToolResultMessage;

/// Records every event of a run and asserts the twelve event invariants over the recording.
///
/// Deliberately framework-free: a failure is a plain [AssertionError] carrying the invariant
/// number, what was expected, what was seen and the offending index, so the same sink works from
/// JUnit, from a host's own harness and from a `main`. Recording is thread-safe because a parallel
/// tool batch emits `ToolUpdate` from several virtual threads at once.
///
/// **Two invariants are weaker here than their one-line statement, and deliberately so.**
///  * I10 (`toolCalls().size() == toolResults().size()`) holds for a turn that *ran* tools. The
///    `Stop`/`Retry` verdicts emit `TurnEnd(assistant, [])` for an assistant that may carry
///    tool-call blocks — a malformed batch is refused before any `ToolStart`. So the check is:
///    a turn with any `ToolStart` must be fully index-aligned; a turn with none must have no
///    results at all.
///  * I9 (no interleaving with steering) is checked as: once a turn has announced a tool call, no
///    message other than a `ToolResultMessage` may appear before its `TurnEnd`. A `Stop` verdict's
///    own message is emitted between the assistant `MessageEnd` and the `TurnEnd` by design, and
///    that turn never ran a tool.
public final class RecordingSink implements EventSink {

    private final Object lock = new Object();
    private final List<AgentEvent> events = new ArrayList<>();
    private boolean closed;
    private int late;

    /// Adapts this sink to [sdk.agent.Agent#subscribe].
    public AgentListener listener() { return this::emit; }

    @Override public void emit(AgentEvent event) {
        synchronized (lock) {
            if (closed) late++;
            events.add(event);
        }
    }

    @Override public void close() {
        synchronized (lock) { closed = true; }
    }

    // ---- observation -------------------------------------------------------------------------

    public List<AgentEvent> events() {
        synchronized (lock) { return List.copyOf(events); }
    }

    public <E extends AgentEvent> List<E> ofType(Class<E> type) {
        return events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    public <E extends AgentEvent> E first(Class<E> type) {
        List<E> all = ofType(type);
        if (all.isEmpty()) throw new AssertionError("no " + type.getSimpleName() + " event; trace=" + trace());
        return all.getFirst();
    }

    /// The compact event-type trace, for golden comparison.
    public List<String> trace() {
        return events().stream().map(e -> e.getClass().getSimpleName()).toList();
    }

    /// Events recorded after [#close] — late emits are counted, never thrown.
    public int lateEvents() {
        synchronized (lock) { return late; }
    }

    public void clear() {
        synchronized (lock) { events.clear(); closed = false; late = 0; }
    }

    /// The text of the tool result recorded for `toolCallId`.
    public Optional<ToolResultMessage> toolResult(String toolCallId) {
        return ofType(AgentEvent.MessageEnd.class).stream()
                .map(AgentEvent.MessageEnd::message)
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(m -> m.toolCallId().equals(toolCallId))
                .findFirst();
    }

    // ---- the twelve invariants ----------------------------------------------------------------

    /// @throws AssertionError naming the invariant, the offending index and the trace
    public void assertInvariants() {
        List<AgentEvent> all = events();
        if (all.isEmpty()) throw fail("I1", "at least a RunStart and a RunEnd", "no events at all", -1, all);

        // I1 / I2 / I12 — the frame.
        if (!(all.getFirst() instanceof AgentEvent.RunStart)) {
            throw fail("I1", "RunStart first", name(all.getFirst()), 0, all);
        }
        long runStarts = all.stream().filter(AgentEvent.RunStart.class::isInstance).count();
        if (runStarts != 1) throw fail("I1", "exactly one RunStart", runStarts + " of them", -1, all);
        long runEnds = all.stream().filter(AgentEvent.RunEnd.class::isInstance).count();
        if (runEnds != 1) throw fail("I2", "exactly one RunEnd", runEnds + " of them", -1, all);
        int endIndex = indexOf(all, AgentEvent.RunEnd.class);
        if (endIndex != all.size() - 1) {
            throw fail("I12", "no event after RunEnd", (all.size() - 1 - endIndex) + " event(s) after it, starting with "
                    + name(all.get(endIndex + 1)), endIndex + 1, all);
        }

        var walk = new Walk(all);
        for (int i = 0; i < all.size(); i++) walk.step(i, all.get(i));
        walk.finish();
    }

    /// One pass, carrying the whole message/turn/tool state. Kept as an inner class so each check
    /// can name the index it failed at.
    private final class Walk {

        private final List<AgentEvent> all;

        private AgentMessage openMessage;
        private boolean openMessageIsAssistant;

        private boolean turnOpen;
        private boolean assistantClosed;                       // this turn's assistant MessageEnd seen
        private boolean turnAnnouncedATool;                    // any ToolStart in this turn
        private final List<String> resultsInTurn = new ArrayList<>();

        private final Set<String> openTools = new LinkedHashSet<>();
        private final Set<String> seenTools = new LinkedHashSet<>();

        private String awaitingResultStart;                    // I6: the id a MessageStart must carry
        private String awaitingResultEnd;

        Walk(List<AgentEvent> all) { this.all = all; }

        void step(int i, AgentEvent e) {
            // I6 — a ToolEnd is immediately followed by the result's MessageStart then MessageEnd.
            if (awaitingResultStart != null) {
                if (!(e instanceof AgentEvent.MessageStart(_, _, _, ToolResultMessage r) && r.toolCallId().equals(awaitingResultStart))) {
                    throw fail("I6", "MessageStart of a ToolResultMessage for " + awaitingResultStart,
                            name(e), i, all);
                }
                awaitingResultEnd = awaitingResultStart;
                awaitingResultStart = null;
            } else if (awaitingResultEnd != null) {
                if (!(e instanceof AgentEvent.MessageEnd(_, _, _, ToolResultMessage r) && r.toolCallId().equals(awaitingResultEnd))) {
                    throw fail("I6", "MessageEnd of a ToolResultMessage for " + awaitingResultEnd, name(e), i, all);
                }
                awaitingResultEnd = null;
            }

            switch (e) {
                case AgentEvent.RunStart _ -> { }

                case AgentEvent.TurnStart _ -> {
                    if (turnOpen) throw fail("I8", "a TurnEnd before the next TurnStart", "a nested TurnStart", i, all);
                    turnOpen = true;
                    assistantClosed = false;
                    turnAnnouncedATool = false;
                    resultsInTurn.clear();
                }

                case AgentEvent.TurnEnd(_, _, _, var assistant, var results) -> {
                    if (!turnOpen) throw fail("I8", "a TurnStart before this TurnEnd", "an unmatched TurnEnd", i, all);
                    checkTurnEnd(i, assistant, results);
                    turnOpen = false;
                }

                case AgentEvent.MessageStart(_, _, _, var message) -> {
                    if (openMessage != null) {
                        throw fail("I3", "a MessageEnd for the open " + message.kind() + " message",
                                "a second MessageStart", i, all);
                    }
                    openMessage = message;
                    openMessageIsAssistant = message instanceof AssistantMessage;
                    checkSteeringIsolation(i, message);
                }

                case AgentEvent.MessageEnd(_, _, _, var message) -> {
                    if (openMessage == null) throw fail("I3", "a MessageStart first", "an unmatched MessageEnd", i, all);
                    if (!openMessage.equals(message) && !(openMessage instanceof AssistantMessage)) {
                        throw fail("I3", "MessageEnd of the message that was started", "MessageEnd of a different message", i, all);
                    }
                    if (message instanceof ToolResultMessage r) resultsInTurn.add(r.toolCallId());
                    if (turnOpen && message instanceof AssistantMessage) assistantClosed = true;
                    openMessage = null;
                    openMessageIsAssistant = false;
                }

                case AgentEvent.MessageUpdate _ -> {
                    if (openMessage == null) throw fail("I4", "a MessageUpdate inside an open message", "one outside any message", i, all);
                    if (!openMessageIsAssistant) {
                        throw fail("I4", "a MessageUpdate only inside an assistant message",
                                "one inside a " + openMessage.kind() + " message", i, all);
                    }
                }

                case AgentEvent.ToolStart(_, _, _, var id, _, _) -> {
                    if (!seenTools.add(id)) throw fail("I5", "one ToolStart per tool call id", "a second ToolStart for " + id, i, all);
                    openTools.add(id);
                    turnAnnouncedATool = true;
                }

                case AgentEvent.ToolUpdate(_, _, _, var id, _, _) -> {
                    if (!openTools.contains(id)) throw fail("I5", "a ToolUpdate between ToolStart and ToolEnd", "one for the unopened id " + id, i, all);
                }

                case AgentEvent.ToolEnd(_, _, _, var id, _, _, _) -> {
                    if (!openTools.remove(id)) throw fail("I5", "an open ToolStart for " + id, "an unmatched ToolEnd", i, all);
                    awaitingResultStart = id;
                }

                case AgentEvent.RunEnd _ -> { }
            }
        }

        /// I9 — once a turn has announced a tool call, only tool results may follow until `TurnEnd`.
        private void checkSteeringIsolation(int i, AgentMessage message) {
            if (!turnOpen || !assistantClosed || !turnAnnouncedATool) return;
            if (message instanceof ToolResultMessage) return;
            throw fail("I9", "only ToolResultMessages between an assistant message and its TurnEnd",
                    "a " + message.kind() + " message", i, all);
        }

        private void checkTurnEnd(int i, AssistantMessage assistant, List<ToolResultMessage> results) {
            List<ContentBlock.ToolCall> calls = assistant.toolCalls();
            if (!turnAnnouncedATool) {
                if (!results.isEmpty()) {
                    throw fail("I7", "no tool results in a turn that announced no tool call",
                            results.size() + " result(s)", i, all);
                }
                return;
            }
            // I10 — index-aligned, sized from toolCalls.
            if (results.size() != calls.size()) {
                throw fail("I10", calls.size() + " tool results (one per tool call)", results.size() + " of them", i, all);
            }
            for (int k = 0; k < calls.size(); k++) {
                if (!calls.get(k).id().equals(results.get(k).toolCallId())) {
                    throw fail("I10", "toolResults[" + k + "] to belong to toolCalls[" + k + "] (" + calls.get(k).id() + ")",
                            results.get(k).toolCallId(), i, all);
                }
            }
            // I7 — exactly the results emitted since the assistant's MessageEnd.
            Set<String> emitted = new LinkedHashSet<>(resultsInTurn);
            Set<String> carried = new LinkedHashSet<>(results.stream().map(ToolResultMessage::toolCallId).toList());
            if (!emitted.equals(carried)) {
                throw fail("I7", "TurnEnd.toolResults to be exactly the results emitted in this turn " + emitted,
                        carried.toString(), i, all);
            }
            // I11 — emitted in assistant source order.
            List<String> sourceOrder = calls.stream().map(ContentBlock.ToolCall::id).filter(emitted::contains).toList();
            if (!sourceOrder.equals(resultsInTurn)) {
                throw fail("I11", "tool results in assistant source order " + sourceOrder, resultsInTurn.toString(), i, all);
            }
        }

        void finish() {
            if (openMessage != null) throw fail("I3", "every MessageStart to be closed", "an open " + openMessage.kind() + " message at RunEnd", -1, all);
            if (!openTools.isEmpty()) throw fail("I5", "every ToolStart to have a ToolEnd", "still open: " + openTools, -1, all);
            if (turnOpen) throw fail("I8", "every TurnStart to have a TurnEnd", "a turn still open at RunEnd", -1, all);
            if (awaitingResultStart != null || awaitingResultEnd != null) {
                throw fail("I6", "the result message after a ToolEnd", "RunEnd instead", -1, all);
            }
        }
    }

    // ---- diagnostics -------------------------------------------------------------------------

    private static AssertionError fail(String invariant, String expected, String actual, int index, List<AgentEvent> all) {
        var message = new StringBuilder(invariant).append(" violated");
        if (index >= 0) message.append(" at event ").append(index);
        message.append(": expected ").append(expected).append(", but found ").append(actual).append('.')
               .append("\n  trace: ").append(all.stream().map(RecordingSink::name).toList());
        if (index >= 0) {
            message.append("\n  event: ").append(all.get(index));
        }
        return new AssertionError(message.toString());
    }

    private static String name(AgentEvent e) { return e.getClass().getSimpleName(); }

    private static int indexOf(List<AgentEvent> all, Class<? extends AgentEvent> type) {
        for (int i = 0; i < all.size(); i++) if (type.isInstance(all.get(i))) return i;
        return -1;
    }
}
