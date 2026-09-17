package sdk.agent.hook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.json.ArgumentException;
import sdk.agent.json.Json;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.StopReason;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolCallSignature;
import sdk.agent.tool.ToolRegistry;

/// Loop and budget policy as an ordinary [AgentHooks] — removable per host, composable, and
/// entirely outside the engine. Six streak tiers ([Thrash]) plus two absolute checks:
///
/// | tier | fires when | verdict |
/// |---|---|---|
/// | `REPEATED` | the last N turns produced the same set of call signatures | `Stop` (THRASH) |
/// | `SAME_TOOL` | the last N calls share a tool name | `Stop` (THRASH) |
/// | `DOMINANT` | one signature accounts for N of the last 10 calls | `Retry` with a warning |
/// | `EMPTY` | no tool calls, blank text | `Retry(CONTINUE_NUDGE)`, `Stop` at the cap |
/// | `MALFORMED` | `TOOL_USE` with tool-call blocks, none usable | `Retry(MALFORMED…)`, `Stop` at the cap |
/// | `TRUNCATED` | `LENGTH` with text and no calls, or `TOOL_USE` with no call block at all | `Retry(TRUNCATED…)`, `Stop` at the cap |
/// | max turns | `turnsUsed >= maxTurns` | `Stop` (MAX_TURNS), no message |
/// | final turn | `turnsUsed == maxTurns - 1` | `beforeRequest` strips the tools and adds a transient instruction |
///
/// Every reprompt is a `UserMessage`, never a system message (several providers reject a
/// mid-conversation system message). The six strings are verbatim from nanocoder, tiny-coding-agent
/// and mini-swe-agent (MIT — see THIRD-PARTY-NOTICES.md).
public final class TurnGuard implements AgentHooks {

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

    /// nanocoder `conversation.ts:459-461`. `%1$s` is the preflight diagnostic in mini-swe-agent's
    /// `<error>` envelope, carrying the offending call; `%2$s` is the rejected tool's schema.
    public static final String MALFORMED_TOOL_CALL_INSTRUCTION =
            "Your previous response contained a malformed tool call. %1$s\n\n%2$s\n\nPlease try again using the correct format.";

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

    public TurnGuard(Map<Thrash, Integer> caps) {
        new ThrashCounters(caps);                         // validates eagerly, naming the missing tier
        this.caps = Map.copyOf(caps);
    }

    public static TurnGuard defaults() { return new TurnGuard(ThrashCounters.defaultCaps()); }

    // ---- the guard point ----------------------------------------------------------------------

    @Override public TurnVerdict afterAssistant(AssistantMessage m, TurnContext ctx) {
        int maxTurns = ctx.limits().maxTurns();
        List<ContentBlock.ToolCall> calls = m.toolCalls();
        // The cap refuses another round of tools; a final answer on the last allowed turn completes cleanly.
        if (ctx.turnsUsed() >= maxTurns && !calls.isEmpty()) {
            return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TURNS, "turn cap reached: " + maxTurns), null);
        }
        RunWatch w = runs.computeIfAbsent(ctx.runId(), _ -> new RunWatch(caps));

        if (m.stopReason() == StopReason.TOOL_USE && calls.isEmpty()) {
            return streak(w, Thrash.TRUNCATED, STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION.formatted(m.stopReason().name().toLowerCase(Locale.ROOT)));
        }
        if (m.stopReason() == StopReason.LENGTH && calls.isEmpty() && !m.text().isBlank()) {
            return streak(w, Thrash.TRUNCATED, TRUNCATED_TURN_INSTRUCTION);
        }
        if (!calls.isEmpty()) {
            List<Optional<String>> problems = calls.stream().map(c -> preflight(c, ctx.tools())).toList();
            if (m.stopReason() == StopReason.TOOL_USE && problems.stream().allMatch(Optional::isPresent)) {
                return streak(w, Thrash.MALFORMED, malformed(calls, problems, ctx.tools()));
            }
            w.record(calls);
            String name = calls.getFirst().name();
            if (w.repeatedBatches() >= caps.get(Thrash.REPEATED)) {
                return stop("identical tool batch on " + caps.get(Thrash.REPEATED) + " consecutive turns", name);
            }
            if (w.sameToolRun() >= caps.get(Thrash.SAME_TOOL)) {
                return stop("same tool on " + caps.get(Thrash.SAME_TOOL) + " consecutive calls", name);
            }
            if (w.dominantCount() >= caps.get(Thrash.DOMINANT)) {
                w.counters.tripped(Thrash.DOMINANT);
                return new TurnVerdict.Retry(UserMessage.text(LOOP_DETECTED.formatted(w.dominantName())), "dominant tool call signature");
            }
            w.counters.healthy();
            return TurnVerdict.PROCEED;
        }
        if (m.text().isBlank() && !m.terminal()) {
            return streak(w, Thrash.EMPTY, CONTINUE_NUDGE);
        }
        w.counters.healthy();
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

    // ---- classification helpers ---------------------------------------------------------------

    private static TurnVerdict streak(RunWatch w, Thrash kind, String reprompt) {
        if (w.counters.tripped(kind)) {
            return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH,
                    kind.name().toLowerCase(Locale.ROOT) + " turn cap reached: " + w.counters.cap(kind)), null);
        }
        return new TurnVerdict.Retry(UserMessage.text(reprompt), kind.name().toLowerCase(Locale.ROOT));
    }

    private static TurnVerdict stop(String detail, String toolName) {
        return new TurnVerdict.Stop(new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH, detail),
                UserMessage.text(LOOP_DETECTED.formatted(toolName)));
    }

    /// "Usable" is decided exactly as the funnel decides it: known name, parseable arguments, and a
    /// schema that accepts them. Empty means usable.
    static Optional<String> preflight(ContentBlock.ToolCall call, ToolRegistry tools) {
        if (call.arguments() == Json.Null.NULL) return Optional.of("the arguments were not valid JSON");
        Optional<Tool<?>> tool = tools.resolve(call.name());
        if (tool.isEmpty()) return Optional.of("there is no tool named `" + call.name() + "`");
        try {
            tool.get().params().bind(tool.get().prepareArguments(call.arguments()));
            return Optional.empty();
        } catch (ArgumentException e) {
            return Optional.of(e.render(call.name()));
        } catch (RuntimeException e) {
            return Optional.of(String.valueOf(e.getMessage()));
        }
    }

    private static String malformed(List<ContentBlock.ToolCall> calls, List<Optional<String>> problems, ToolRegistry tools) {
        var error = new StringBuilder("<error>\n");
        for (int i = 0; i < calls.size(); i++) {
            var c = calls.get(i);
            error.append("Tool call `").append(c.name()).append("` (id ").append(c.id()).append("): ")
                 .append(problems.get(i).orElse("")).append('\n')
                 .append("Arguments: ").append(c.arguments().toText()).append('\n');
        }
        error.append("</error>");

        Set<String> names = calls.stream().map(ContentBlock.ToolCall::name).collect(Collectors.toCollection(LinkedHashSet::new));
        var examples = new ArrayList<String>();
        for (String name : names) {
            tools.resolve(name).ifPresentOrElse(
                    t -> examples.add("Expected input schema for `" + name + "`:\n" + t.params().schema().toPrettyText()),
                    () -> examples.add("Available tools: " + String.join(", ", tools.names())));
        }
        return MALFORMED_TOOL_CALL_INSTRUCTION.formatted(error, String.join("\n\n", examples));
    }

    /// Per-run window and counters. Touched only by the driver thread of its run.
    private static final class RunWatch {
        final ThrashCounters counters;
        final Deque<Set<ToolCallSignature>> batches = new ArrayDeque<>();
        final Deque<Call> calls = new ArrayDeque<>();

        RunWatch(Map<Thrash, Integer> caps) { counters = new ThrashCounters(caps); }

        private record Call(String name, ToolCallSignature signature) { }

        void record(List<ContentBlock.ToolCall> toolCalls) {
            var batch = new LinkedHashSet<ToolCallSignature>();
            for (var c : toolCalls) {
                var sig = ToolCallSignature.of(c.name(), c.arguments());
                batch.add(sig);
                calls.addLast(new Call(c.name(), sig));
                if (calls.size() > WINDOW) calls.removeFirst();
            }
            batches.addLast(batch);
            if (batches.size() > WINDOW) batches.removeFirst();
        }

        /// How many of the most recent batches, counted from the end, equal the last one.
        int repeatedBatches() {
            if (batches.isEmpty()) return 0;
            Set<ToolCallSignature> last = batches.peekLast();
            int n = 0;
            for (var it = batches.descendingIterator(); it.hasNext() && it.next().equals(last); ) n++;
            return n;
        }

        /// Length of the run of most recent calls sharing the last call's tool name.
        int sameToolRun() {
            if (calls.isEmpty()) return 0;
            String last = calls.peekLast().name();
            int n = 0;
            for (var it = calls.descendingIterator(); it.hasNext() && it.next().name().equals(last); ) n++;
            return n;
        }

        int dominantCount() { return dominant().map(Map.Entry::getValue).orElse(0); }

        String dominantName() {
            return dominant().map(e -> calls.stream().filter(c -> c.signature().equals(e.getKey())).findFirst().map(Call::name).orElse("unknown")).orElse("unknown");
        }

        private Optional<Map.Entry<ToolCallSignature, Integer>> dominant() {
            var counts = new HashMap<ToolCallSignature, Integer>();
            for (Call c : calls) counts.merge(c.signature(), 1, Integer::sum);
            return counts.entrySet().stream().max(Map.Entry.comparingByValue());
        }
    }
}
