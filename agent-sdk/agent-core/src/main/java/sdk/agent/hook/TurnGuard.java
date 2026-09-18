package sdk.agent.hook;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/// Loop and response-quality policy as an ordinary [AgentHooks] — removable per host, composable,
/// and entirely outside the engine (which enforces the turn, tool-call and wall-clock budgets
/// itself). Four tiers ([Thrash]) plus the final-turn rule:
///
/// | tier | fires when | verdict |
/// |---|---|---|
/// | `REPEATED` | the same set of call signatures on N consecutive tool turns | `Stop` (THRASH) |
/// | `DOMINANT` | one signature accounts for N of the last 10 calls | `Retry` with a warning |
/// | `EMPTY` | no tool calls, blank text | `Retry(CONTINUE_NUDGE)`, `Stop` at the cap |
/// | `TRUNCATED` | `LENGTH`, or `TOOL_USE` with no call block at all | `Retry(TRUNCATED…)`, `Stop` at the cap |
/// | max turns | `turnsUsed >= maxTurns` and the model wants more tools | `Stop` (MAX_TURNS), no message |
/// | final turn | `turnsUsed == maxTurns - 1` | `beforeRequest` strips the tools and adds a transient instruction |
///
/// Tool validity is not judged here: the funnel reports an unknown tool or unusable arguments to
/// the model as that call's result, which is the format correction it needs. An `EMPTY` or
/// `TRUNCATED` turn increments its counter and zeroes the other; a healthy turn decays both, so a
/// model alternating good and bad turns cannot burn the budget without tripping a cap. Every
/// reprompt is a `UserMessage`, never a system message. The strings are verbatim from nanocoder,
/// tiny-coding-agent and mini-swe-agent (MIT — see THIRD-PARTY-NOTICES.md).
public final class TurnGuard implements AgentHooks {

    public enum Thrash { REPEATED, DOMINANT, EMPTY, TRUNCATED }

    public static final Map<Thrash, Integer> DEFAULT_CAPS = Map.of(
            Thrash.REPEATED, 3, Thrash.DOMINANT, 8, Thrash.EMPTY, 2, Thrash.TRUNCATED, 2);

    /// nanocoder `conversation.ts:108-113`
    public static final String TRUNCATED_TURN_INSTRUCTION = """
            Your previous reply was cut off at the output-token limit before it finished. Continue \
            from exactly where it stopped. Do not repeat or summarise what you already wrote, and \
            keep any remaining explanation brief. If the task requires a tool call to be complete, \
            make that call now rather than describing what it would do.""";

    /// nanocoder `conversation.ts:98-101`
    public static final String FINAL_TURN_INSTRUCTION = """
            You have reached the maximum number of tool-execution turns for this run. Do not call \
            any more tools. Produce your final answer now using only the information you already have.""";

    /// nanocoder `conversation.ts:582`
    public static final String CONTINUE_NUDGE = "Please continue with the task.";

    /// mini-swe-agent `config/default.yaml:144-146`, the tool-call-less branch. `%s` is the stop reason as reported.
    public static final String STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION = """
            Your previous response reached the output token limit (finish_reason=%s) before you \
            produced a complete action, so it was cut off. Respond more concisely and provide \
            exactly one action in the required format. If you need to think more, do so briefly.""";

    /// tiny-coding-agent `turn-executor.ts:203`
    public static final String LOOP_DETECTED = """
            STOP: You have called %s repeatedly with the same arguments. Please stop and use the \
            results you already have, or try a different approach. Provide your final answer now \
            based on the information you have gathered.""";

    private static final int WINDOW = 10;

    private final Map<Thrash, Integer> caps;
    private final ConcurrentMap<String, RunWatch> runs = new ConcurrentHashMap<>();

    /// @throws IllegalArgumentException naming the first tier whose cap is missing or below one
    public TurnGuard(Map<Thrash, Integer> caps) {
        for (Thrash t : Thrash.values()) {
            Integer cap = caps.get(t);
            if (cap == null || cap < 1) throw new IllegalArgumentException("cap for " + t + " must be >= 1, was " + cap);
        }
        this.caps = Map.copyOf(caps);
    }

    public static TurnGuard defaults() { return new TurnGuard(DEFAULT_CAPS); }

    // ---- the guard point ----------------------------------------------------------------------

    @Override public TurnVerdict afterAssistant(AssistantMessage m, TurnContext ctx) {
        int maxTurns = ctx.limits().maxTurns();
        List<ContentBlock.ToolCall> calls = m.toolCalls();
        // The cap refuses another round of tools; a final answer on the last allowed turn completes cleanly.
        if (ctx.turnsUsed() >= maxTurns && !calls.isEmpty()) {
            return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TURNS, "turn cap reached: " + maxTurns), null);
        }
        RunWatch w = runs.computeIfAbsent(ctx.runId(), _ -> new RunWatch());
        Instant now = ctx.clock().instant();

        if (m.stopReason() == StopReason.TOOL_USE && calls.isEmpty()) {
            return streak(w, Thrash.TRUNCATED, STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION.formatted(m.stopReason().name().toLowerCase(Locale.ROOT)), now);
        }
        if (m.stopReason() == StopReason.LENGTH) {                    // with or without calls: whatever it holds is incomplete
            return streak(w, Thrash.TRUNCATED, TRUNCATED_TURN_INSTRUCTION, now);
        }
        if (!calls.isEmpty()) {
            w.record(calls);
            if (w.repeated >= caps.get(Thrash.REPEATED)) {
                return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH,
                        "identical tool batch on " + caps.get(Thrash.REPEATED) + " consecutive turns"),
                        UserMessage.text(LOOP_DETECTED.formatted(calls.getFirst().name()), now));
            }
            Optional<String> dominant = w.dominant(caps.get(Thrash.DOMINANT));
            if (dominant.isPresent()) {
                return new TurnVerdict.Retry(UserMessage.text(LOOP_DETECTED.formatted(dominant.get()), now), "dominant tool call signature");
            }
            w.healthy();
            return TurnVerdict.PROCEED;
        }
        w.noCalls();
        if (m.text().isBlank() && !m.terminal()) {
            return streak(w, Thrash.EMPTY, CONTINUE_NUDGE, now);
        }
        w.healthy();
        return TurnVerdict.PROCEED;
    }

    /// The final-turn strip: a **copy** of the request with no tools and a transient instruction.
    /// Nothing is appended to the transcript, so the guard is idempotent under resume.
    @Override public LlmRequest beforeRequest(LlmRequest r, TurnContext ctx) {
        if (ctx.turnsUsed() != ctx.limits().maxTurns() - 1) return r;
        return r.withTools(List.of())
                .withMessages(Stream.concat(r.messages().stream(), Stream.of(UserMessage.text(FINAL_TURN_INSTRUCTION, ctx.clock().instant()))).toList());
    }

    @Override public void onEvent(AgentEvent event) {
        if (event instanceof AgentEvent.RunEnd end) runs.remove(end.runId());
    }

    private TurnVerdict streak(RunWatch w, Thrash kind, String reprompt, Instant now) {
        w.noCalls();
        if (w.tripped(kind) >= caps.get(kind)) {
            return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH,
                    kind.name().toLowerCase(Locale.ROOT) + " turn cap reached: " + caps.get(kind)), null);
        }
        return new TurnVerdict.Retry(UserMessage.text(reprompt, now), kind.name().toLowerCase(Locale.ROOT));
    }

    /// Per-run window and counters. Touched only by the driver thread of its run.
    private static final class RunWatch {
        private record Call(String name, ToolCallSignature signature) { }

        final Deque<Call> calls = new ArrayDeque<>();                    // the last WINDOW calls, for DOMINANT
        Set<ToolCallSignature> lastBatch = Set.of();
        int repeated, empty, truncated;

        /// A failing turn of `kind`: increment mine, zero the other. Returns the new count.
        int tripped(Thrash kind) {
            if (kind == Thrash.EMPTY) { truncated = 0; return ++empty; }
            empty = 0;
            return ++truncated;
        }

        /// A healthy turn: decay both counters by one.
        void healthy() {
            empty = Math.max(0, empty - 1);
            truncated = Math.max(0, truncated - 1);
        }

        /// A turn without calls breaks a batch streak.
        void noCalls() {
            repeated = 0;
            lastBatch = Set.of();
        }

        void record(List<ContentBlock.ToolCall> toolCalls) {
            var batch = new HashSet<ToolCallSignature>();
            for (var c : toolCalls) {
                var sig = ToolCallSignature.of(c.name(), c.arguments());
                batch.add(sig);
                calls.addLast(new Call(c.name(), sig));
                if (calls.size() > WINDOW) calls.removeFirst();
            }
            repeated = batch.equals(lastBatch) ? repeated + 1 : 1;
            lastBatch = batch;
        }

        /// The tool name of a signature seen at least `cap` times in the window, if any.
        Optional<String> dominant(int cap) {
            var counts = new HashMap<ToolCallSignature, Integer>();
            for (Call c : calls) counts.merge(c.signature(), 1, Integer::sum);
            return counts.entrySet().stream().filter(e -> e.getValue() >= cap).map(Map.Entry::getKey).findFirst()
                    .flatMap(sig -> calls.stream().filter(c -> c.signature().equals(sig)).map(Call::name).findFirst());
        }
    }
}
