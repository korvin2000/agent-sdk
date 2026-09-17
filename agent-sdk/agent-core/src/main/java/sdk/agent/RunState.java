package sdk.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import sdk.agent.event.RunOutcome;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.Usage;
import sdk.agent.turn.TurnPhase;
import sdk.agent.turn.TurnState;

/// Everything about a run that is a **value**: serialisable at every `advance()` boundary,
/// resumable with fresh collaborators ([RunDeps]).
///
/// `transcript` is loop-owned and never aliases the caller's list; its first `seedSize` entries were
/// inherited from earlier runs and everything after them is what this run [#produced].
/// `pendingInjection` holds messages to append at the next turn opening.
public record RunState(String runId,
                       Phase phase,
                       int turnIndex,
                       List<AgentMessage> transcript,
                       int seedSize,
                       List<AgentMessage> pendingInjection,
                       TurnState turn,
                       RunLimits limits,
                       int turnsUsed,
                       int toolCallsUsed,
                       Usage usage,
                       Instant startedAt,
                       RunOutcome outcome,
                       String toolSetHash) {

    public RunState {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        transcript = List.copyOf(transcript);
        if (seedSize < 0 || seedSize > transcript.size()) throw new IllegalArgumentException("seedSize out of range: " + seedSize);
        pendingInjection = List.copyOf(pendingInjection);
        Objects.requireNonNull(limits, "limits");
        usage = Objects.requireNonNullElse(usage, Usage.EMPTY);
        Objects.requireNonNull(startedAt, "startedAt");
    }

    /// The messages this run appended, complete on every path; what `RunEnd` reports.
    public List<AgentMessage> produced() { return transcript.subList(seedSize, transcript.size()); }

    public boolean finished() { return phase == Phase.FINISHED; }

    public Optional<AssistantMessage> lastAssistant() {
        return Optional.ofNullable(turn).map(TurnState::assistant);
    }

    /// `true` while a turn is open, i.e. a `TurnEnd` is still owed.
    public boolean turnOpen() { return turn != null && turn.phase() != TurnPhase.CLOSED; }

    Builder toBuilder() { return new Builder(this); }

    /// Package-private mutable copy for the engine's rows.
    static final class Builder {
        final String runId; Phase phase; int turnIndex;
        final List<AgentMessage> transcript; final int seedSize; final List<AgentMessage> pendingInjection;
        TurnState turn; final RunLimits limits; int turnsUsed; int toolCallsUsed; Usage usage;
        final Instant startedAt; RunOutcome outcome; final String toolSetHash;

        private Builder(RunState s) {
            runId = s.runId; phase = s.phase; turnIndex = s.turnIndex;
            transcript = new ArrayList<>(s.transcript); seedSize = s.seedSize;
            pendingInjection = new ArrayList<>(s.pendingInjection);
            turn = s.turn; limits = s.limits; turnsUsed = s.turnsUsed; toolCallsUsed = s.toolCallsUsed; usage = s.usage;
            startedAt = s.startedAt; outcome = s.outcome; toolSetHash = s.toolSetHash;
        }

        RunState build() {
            return new RunState(runId, phase, turnIndex, transcript, seedSize, pendingInjection, turn, limits,
                    turnsUsed, toolCallsUsed, usage, startedAt, outcome, toolSetHash);
        }
    }
}
