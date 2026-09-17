package sdk.agent.event;

import java.time.Instant;
import java.util.List;

import sdk.agent.json.Json;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ToolResult;

/// The ten events a run emits — the same ten as pi-mono. Every event carries `runId` and
/// `turnIndex` (`-1` for run-scoped events) so two loops can share one sink and a session can be
/// rebuilt from a persisted event log. There is exactly one terminal event, [RunEnd], whose
/// `produced` list is complete on every path including failure.
public sealed interface AgentEvent {

    String runId();
    int turnIndex();
    Instant at();

    int RUN_SCOPED = -1;

    record RunStart(String runId, int turnIndex, Instant at) implements AgentEvent { }

    record RunEnd(String runId, int turnIndex, Instant at,
                  List<AgentMessage> produced, RunOutcome outcome) implements AgentEvent {
        public RunEnd { produced = List.copyOf(produced); }
    }

    record TurnStart(String runId, int turnIndex, Instant at) implements AgentEvent { }

    record TurnEnd(String runId, int turnIndex, Instant at,
                   AssistantMessage message, List<ToolResultMessage> toolResults) implements AgentEvent {
        public TurnEnd { toolResults = List.copyOf(toolResults); }
    }

    record MessageStart(String runId, int turnIndex, Instant at, AgentMessage message) implements AgentEvent { }

    /// `partial` is the core's own snapshot of the assistant message so far, not provider-supplied.
    record MessageUpdate(String runId, int turnIndex, Instant at,
                         AssistantMessage partial, LlmStreamEvent delta) implements AgentEvent { }

    record MessageEnd(String runId, int turnIndex, Instant at, AgentMessage message) implements AgentEvent { }

    /// `rawArguments` is always present. `boundArguments` is null until binding succeeds — and
    /// `ToolStart` is emitted before prepare/bind, so on the engine's own path it is always null.
    /// A host that needs both in one place uses the `BeforeToolCall` hook.
    record ToolStart(String runId, int turnIndex, Instant at, String toolCallId,
                     String toolName, Json rawArguments, Json boundArguments) implements AgentEvent { }

    record ToolUpdate(String runId, int turnIndex, Instant at, String toolCallId,
                      String toolName, ToolResult partial) implements AgentEvent { }

    record ToolEnd(String runId, int turnIndex, Instant at, String toolCallId,
                   String toolName, ToolResult result, boolean isError) implements AgentEvent { }
}
