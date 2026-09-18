package sdk.agent.hook;

import java.util.List;
import java.util.Optional;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.AgentEvent;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.provider.LlmRequest;

/// The one hook interface. Skills, permissions, sandboxing, git snapshotting, TODO tracking, loop
/// detection and compaction are all implementations of this, registered into a [CompositeHooks],
/// with zero core edits. Decision hooks **fail closed**: a throw from [#beforeToolCall] or
/// [#afterToolCall] becomes that call's `HOOK_FAILED` result, a throw from any other decision ends
/// the run as `Failed` — never a permissive fallback, and never a lost `ToolEnd`. Only [#onEvent]
/// is isolated.
public interface AgentHooks {

    AgentHooks NONE = new AgentHooks() { };

    /// Per-request, **non-destructive**: feeds the converter for one request and never replaces the
    /// working transcript. **This is the compaction seam.**
    default List<AgentMessage> transformContext(List<AgentMessage> messages, Cancellation cancel) { return messages; }

    /// Per-turn, **durable** injection before the request is built (skills, reminders). Appended
    /// to the transcript once, emitted once, never re-emitted.
    default List<AgentMessage> beforeTurn(TurnContext ctx) { return List.of(); }

    /// **The request seam.** The assembled request, immediately before the stream opens. Return a
    /// modified copy (`with*` rebuilds). Messages are already converted and must not be re-derived
    /// here — that is [#transformContext]'s job. Whatever this adds is transient: one request only.
    default LlmRequest beforeRequest(LlmRequest request, TurnContext ctx) { return request; }

    /// **The guard point.** After the assistant message is final, before any tool executes — so a
    /// veto has no side effects to undo.
    default TurnVerdict afterAssistant(AssistantMessage message, TurnContext ctx) { return TurnVerdict.PROCEED; }

    /// After validation, before execute, **sequentially in source order even inside a parallel
    /// batch**. This is where a permissions module plugs in, and nowhere else.
    default ToolDecision beforeToolCall(BeforeToolCall call, Cancellation cancel) { return ToolDecision.ALLOW; }

    /// Partial override of a result. Omitted fields keep the original; no deep merge.
    default Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation cancel) { return Optional.empty(); }

    default void onEvent(AgentEvent event) { }
}
