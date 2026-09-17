package sdk.agent.turn;

import java.util.List;

import sdk.agent.event.AgentEvent;

/// The result of one [TurnMachine#step]. Every input is validated against the phase: an illegal
/// one yields [Ignored] with a diagnostic and an unchanged state, never an exception, so a driver
/// defect cannot corrupt a turn.
public sealed interface StepOutcome {

    List<AgentEvent> events();

    TurnState state();

    record Needs(List<AgentEvent> events, TurnState state, Need need) implements StepOutcome {
        public Needs { events = List.copyOf(events); }
    }

    record Finished(List<AgentEvent> events, TurnState state) implements StepOutcome {
        public Finished { events = List.copyOf(events); }
    }

    record Ignored(String reason, TurnState state) implements StepOutcome {
        @Override public List<AgentEvent> events() { return List.of(); }
    }
}
