package sdk.agent.hook;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.StopReason;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.tool.ToolCallSignature;

/// Loop and response-quality policy as an ordinary [AgentHooks].
///
/// Repeated ordered tool batches stop after three consecutive matches. Empty and truncated
/// responses are reprompted twice before stopping. Tool-name-only and schema preflight heuristics
/// deliberately do not belong here: the funnel owns tool validity and authority.
public final class TurnGuard implements AgentHooks {

    public enum Thrash { REPEATED, EMPTY, TRUNCATED }

    public static final Map<Thrash, Integer> DEFAULT_CAPS = Map.of(
            Thrash.REPEATED, 3, Thrash.EMPTY, 2, Thrash.TRUNCATED, 2);

    public static final String TRUNCATED_TURN_INSTRUCTION = """
            Your previous reply was cut off at the output-token limit before it finished. Continue \
            from exactly where it stopped. Do not repeat or summarise what you already wrote, and \
            keep any remaining explanation brief. If the task requires a tool call to be complete, \
            make that call now rather than describing what it would do.""";

    public static final String FINAL_TURN_INSTRUCTION = """
            You have reached the maximum number of tool-execution turns for this run. Do not call \
            any more tools. Produce your final answer now using only the information you already have.""";

    public static final String CONTINUE_NUDGE = "Please continue with the task.";

    public static final String STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION = """
            Your previous response reached the output token limit (finish_reason=%s) before you \
            produced a complete action, so it was cut off. Respond more concisely and provide \
            exactly one action in the required format. If you need to think more, do so briefly.""";

    public static final String LOOP_DETECTED = """
            STOP: You have called %s repeatedly with the same arguments. Please stop and use the \
            results you already have, or try a different approach. Provide your final answer now \
            based on the information you have gathered.""";

    private final Map<Thrash, Integer> caps;
    private final ConcurrentMap<String, RunWatch> runs = new ConcurrentHashMap<>();

    public TurnGuard(Map<Thrash, Integer> caps) {
        for (Thrash t : Thrash.values()) {
            Integer cap = caps.get(t);
            if (cap == null || cap < 1) {
                throw new IllegalArgumentException("cap for " + t + " must be >= 1, was " + cap);
            }
        }
        this.caps = Map.copyOf(caps);
    }

    public static TurnGuard defaults() { return new TurnGuard(DEFAULT_CAPS); }

    @Override public TurnVerdict afterAssistant(AssistantMessage m, TurnContext ctx) {
        int maxTurns = ctx.limits().maxTurns();
        List<ContentBlock.ToolCall> calls = m.toolCalls();
        if (ctx.turnsUsed() >= maxTurns && !calls.isEmpty()) {
            return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TURNS,
                    "turn cap reached: " + maxTurns), null);
        }

        RunWatch w = runs.computeIfAbsent(ctx.runId(), _ -> new RunWatch(caps));
        Instant now = ctx.clock().instant();
        if (m.stopReason() == StopReason.TOOL_USE && calls.isEmpty()) {
            w.breakBatch();
            return streak(w, Thrash.TRUNCATED,
                    STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION.formatted(m.stopReason().name().toLowerCase(Locale.ROOT)), now);
        }
        if (m.stopReason() == StopReason.LENGTH) {
            w.breakBatch();
            return streak(w, Thrash.TRUNCATED, TRUNCATED_TURN_INSTRUCTION, now);
        }
        if (!calls.isEmpty()) {
            w.record(calls);
            if (w.repeatedBatches() >= caps.get(Thrash.REPEATED)) {
                return stop("identical tool batch on " + caps.get(Thrash.REPEATED) + " consecutive turns",
                        calls.getFirst().name(), now);
            }
            w.healthy();
            return TurnVerdict.PROCEED;
        }
        w.breakBatch();
        if (m.text().isBlank() && !m.terminal()) {
            return streak(w, Thrash.EMPTY, CONTINUE_NUDGE, now);
        }
        w.healthy();
        return TurnVerdict.PROCEED;
    }

    /// The final-turn strip: a copy of the request with no tools and a transient instruction.
    @Override public LlmRequest beforeRequest(LlmRequest r, TurnContext ctx) {
        if (ctx.turnsUsed() != ctx.limits().maxTurns() - 1) return r;
        return r.withTools(List.of()).withMessages(Stream.concat(r.messages().stream(),
                Stream.of(UserMessage.text(FINAL_TURN_INSTRUCTION, ctx.clock().instant()))).toList());
    }

    @Override public void onEvent(AgentEvent event) {
        if (event instanceof AgentEvent.RunEnd end) runs.remove(end.runId());
    }

    private static TurnVerdict streak(RunWatch w, Thrash kind, String reprompt, Instant now) {
        if (w.tripped(kind)) {
            return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH,
                    kind.name().toLowerCase(Locale.ROOT) + " turn cap reached: " + w.caps.get(kind)), null);
        }
        return new TurnVerdict.Retry(UserMessage.text(reprompt, now), kind.name().toLowerCase(Locale.ROOT));
    }

    private static TurnVerdict stop(String detail, String toolName, Instant now) {
        return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH, detail),
                UserMessage.text(LOOP_DETECTED.formatted(toolName), now));
    }

    private static final class RunWatch {
        final Map<Thrash, Integer> caps;
        List<ToolCallSignature> lastBatch = List.of();
        int repeatedBatches;
        int emptyStreak;
        int truncatedStreak;

        RunWatch(Map<Thrash, Integer> caps) { this.caps = caps; }

        boolean tripped(Thrash kind) {
            int n = switch (kind) {
                case EMPTY -> ++emptyStreak;
                case TRUNCATED -> ++truncatedStreak;
                case REPEATED -> repeatedBatches;
            };
            if (kind == Thrash.EMPTY) truncatedStreak = 0;
            if (kind == Thrash.TRUNCATED) emptyStreak = 0;
            return n >= caps.get(kind);
        }

        void healthy() {
            emptyStreak = Math.max(0, emptyStreak - 1);
            truncatedStreak = Math.max(0, truncatedStreak - 1);
        }

        void breakBatch() {
            repeatedBatches = 0;
            lastBatch = List.of();
        }

        void record(List<ContentBlock.ToolCall> calls) {
            List<ToolCallSignature> batch = calls.stream()
                    .map(c -> ToolCallSignature.of(c.name(), c.arguments())).toList();
            repeatedBatches = batch.equals(lastBatch) ? repeatedBatches + 1 : 1;
            lastBatch = batch;
        }

        int repeatedBatches() { return repeatedBatches; }
    }
}
