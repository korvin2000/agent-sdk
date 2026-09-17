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
/// resumable with fresh collaborators. The split from [RunDeps] is the entire trick — pi's context
/// mixes the transcript with live tool objects, so nothing in it can be persisted or replayed.
///
/// `transcript` is loop-owned and never aliases the caller's list; `produced` is what `RunEnd`
/// reports on every path; `pendingInjection` holds messages to append at the next turn opening.
public record RunState(String runId,
                       Phase phase,
                       int turnIndex,
                       List<AgentMessage> transcript,
                       List<AgentMessage> produced,
                       List<AgentMessage> pendingInjection,
                       TurnState turn,
                       RunLimits limits,
                       int turnsUsed,
                       int toolCallsUsed,
                       Usage usage,
                       Instant startedAt,
                       boolean skipInitialSteeringPoll,
                       RunOutcome outcome,
                       int schemaVersion,
                       String toolSetHash) {

    public static final int SCHEMA_VERSION = 1;

    public RunState {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        transcript = List.copyOf(transcript);
        produced = List.copyOf(produced);
        pendingInjection = List.copyOf(pendingInjection);
        Objects.requireNonNull(limits, "limits");
        usage = Objects.requireNonNullElse(usage, Usage.EMPTY);
        Objects.requireNonNull(startedAt, "startedAt");
    }

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
        final List<AgentMessage> transcript; final List<AgentMessage> produced; final List<AgentMessage> pendingInjection;
        TurnState turn; final RunLimits limits; int turnsUsed; int toolCallsUsed; Usage usage;
        final Instant startedAt; final boolean skipInitialSteeringPoll; RunOutcome outcome;
        final int schemaVersion; final String toolSetHash;

        private Builder(RunState s) {
            runId = s.runId; phase = s.phase; turnIndex = s.turnIndex;
            transcript = new ArrayList<>(s.transcript); produced = new ArrayList<>(s.produced);
            pendingInjection = new ArrayList<>(s.pendingInjection);
            turn = s.turn; limits = s.limits; turnsUsed = s.turnsUsed; toolCallsUsed = s.toolCallsUsed; usage = s.usage;
            startedAt = s.startedAt; skipInitialSteeringPoll = s.skipInitialSteeringPoll; outcome = s.outcome;
            schemaVersion = s.schemaVersion; toolSetHash = s.toolSetHash;
        }

        RunState build() {
            return new RunState(runId, phase, turnIndex, transcript, produced, pendingInjection, turn, limits,
                    turnsUsed, toolCallsUsed, usage, startedAt, skipInitialSteeringPoll, outcome, schemaVersion, toolSetHash);
        }
    }
}
