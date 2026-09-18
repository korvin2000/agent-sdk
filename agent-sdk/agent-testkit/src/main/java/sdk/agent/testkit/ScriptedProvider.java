package sdk.agent.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.StopReason;
import sdk.agent.message.Usage;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStream;
import sdk.agent.provider.LlmStreamEvent;

/// A provider whose whole behaviour is a **script**: a list of [Step]s answered one per
/// [LlmStream#next]. It is the counterpart of the pure [sdk.agent.turn.TurnMachine]: a hang is a
/// marker rather than a sleep, so the idle-timeout path runs in milliseconds; attempt state resets
/// per `stream()` call; `usage` varies per scenario.
///
/// A [Step.FailOpen] is a failure of the **open** call: `stream()` throws it, after first burning
/// up to [#MAX_ATTEMPTS] retryable attempts internally. That keeps retry policy behind the seam and
/// leaves [LlmStreamEvent.Failed] free to mean *mid-stream* failure — the two are different code
/// paths in the engine.
public final class ScriptedProvider implements LlmProvider {

    /// One instruction of a script.
    public sealed interface Step {

        /// Hand this event to the consumer.
        record Emit(LlmStreamEvent event) implements Step {
            public Emit { Objects.requireNonNull(event, "event"); }
        }

        /// The `stream()` call fails. `retryable` lets the provider's own backoff swallow it and
        /// move to the next step; the last attempt (or a non-retryable failure) throws.
        record FailOpen(RuntimeException error, boolean retryable) implements Step {
            public FailOpen { Objects.requireNonNull(error, "error"); }
        }

        /// End of stream with **no terminal event** — the engine's `StreamExhausted` path.
        record Exhaust() implements Step { }

        /// `next()` blocks until `close()`. With a tool call in flight the engine's idle timeout
        /// turns this into a stalled turn; with none, only cancellation ends it.
        record Hang() implements Step { }
    }

    /// How many opens one script may burn on retryable failures before `stream()` gives up.
    public static final int MAX_ATTEMPTS = 3;

    private static final List<Step> AFTER_LAST_SCRIPT = List.of(
            emit(new LlmStreamEvent.Start()),
            emit(new LlmStreamEvent.TextStart(0)),
            emit(new LlmStreamEvent.TextDelta(0, "Done.")),
            emit(new LlmStreamEvent.TextEnd(0, "Done.", null)),
            emit(new LlmStreamEvent.Done(StopReason.STOP, Usage.tokens(8, 2), "scripted-stop")));

    private final List<List<Step>> scripts;
    private final AtomicInteger opens = new AtomicInteger();
    private final List<Integer> attempts = new CopyOnWriteArrayList<>();
    private final List<ScriptedStream> streams = new CopyOnWriteArrayList<>();

    private ScriptedProvider(List<List<Step>> scripts) {
        this.scripts = scripts.stream().map(List::copyOf).toList();
    }

    // ---- construction ------------------------------------------------------------------------

    /// One script, answered on the first `stream()`; every later call gets a plain text
    /// `Done(STOP)` turn so a multi-turn run terminates instead of replaying the tool calls.
    public static ScriptedProvider of(List<Step> script) { return new ScriptedProvider(List.of(script)); }

    public static ScriptedProvider of(Step... script) { return of(List.of(script)); }

    /// Successive `stream()` calls are answered by successive scripts; after the last one, a plain
    /// text `Done(STOP)` turn repeats.
    public static ScriptedProvider sequence(List<List<Step>> scripts) { return new ScriptedProvider(scripts); }

    public static Step emit(LlmStreamEvent event)                 { return new Step.Emit(event); }
    public static Step failOpen(RuntimeException e, boolean retry) { return new Step.FailOpen(e, retry); }
    public static Step exhaust()                                  { return new Step.Exhaust(); }
    public static Step hang()                                     { return new Step.Hang(); }

    // ---- the seam ----------------------------------------------------------------------------

    @Override public LlmStream stream(LlmRequest request, Cancellation cancel) {
        Objects.requireNonNull(request, "request");
        int index = opens.getAndIncrement();
        List<Step> script = index < scripts.size() ? scripts.get(index) : AFTER_LAST_SCRIPT;

        int position = 0;
        int attempt = 0;                                   // per open, so a reused provider cannot carry attempts over
        while (position < script.size() && script.get(position) instanceof Step.FailOpen(var error, var retryable)) {
            attempt++;
            if (!retryable || attempt >= MAX_ATTEMPTS) {
                attempts.add(attempt);
                throw error;
            }
            position++;
        }
        attempts.add(attempt);
        var stream = new ScriptedStream(script.subList(position, script.size()));
        streams.add(stream);
        return stream;
    }

    // ---- observation -------------------------------------------------------------------------

    /// The first script; what [#of] was built from.
    public List<Step> script() { return scripts.getFirst(); }

    public List<List<Step>> scripts() { return scripts; }

    /// How many times `stream()` has been called.
    public int opens() { return opens.get(); }

    /// Failed opens burned per `stream()` call, in call order — `[2, 2]` proves the counter resets.
    public List<Integer> attemptsPerOpen() { return List.copyOf(attempts); }

    /// True once every stream handed out has been closed (the pump closes one per turn).
    public boolean allStreamsClosed() { return streams.stream().allMatch(ScriptedStream::isClosed); }

    // ---- the stream --------------------------------------------------------------------------

    private static final class ScriptedStream implements LlmStream {

        private final List<Step> steps;
        private final CountDownLatch closed = new CountDownLatch(1);
        private int cursor;

        ScriptedStream(List<Step> steps) { this.steps = steps; }

        @Override public LlmStreamEvent next() throws InterruptedException {
            while (true) {
                if (closed.getCount() == 0) return null;
                if (cursor >= steps.size()) return null;
                switch (steps.get(cursor++)) {
                    case Step.Emit(var event)     -> { return event; }
                    case Step.Exhaust _           -> { return null; }
                    case Step.Hang _              -> { closed.await(); return null; }
                    // A mid-script open failure degrades to the in-band shape; the contract of
                    // FailOpen is about opening, and `next()` may not throw an unchecked failure.
                    case Step.FailOpen(var e, _)  -> { return new LlmStreamEvent.Failed(StopReason.ERROR, describe(e)); }
                }
            }
        }

        @Override public void close() { closed.countDown(); }

        boolean isClosed() { return closed.getCount() == 0; }
    }

    private static String describe(Throwable t) {
        return t.getMessage() == null || t.getMessage().isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    // ---- the eighteen scenarios ---------------------------------------------------------------

    /// **1.** Thinking → text → **two** tool calls (`read`, `bash`) → `Done(TOOL_USE)`.
    /// Pins the happy path, parallel calls, content-index keying and the I10 call/result pairing.
    public static ScriptedProvider defaultScenario() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.ThinkingStart(0)),
                emit(new LlmStreamEvent.ThinkingDelta(0, "Two files to look at.")),
                emit(new LlmStreamEvent.ThinkingEnd(0, "Two files to look at.", "think-sig", false)),
                emit(new LlmStreamEvent.TextStart(1)),
                emit(new LlmStreamEvent.TextDelta(1, "Reading both.")),
                emit(new LlmStreamEvent.TextEnd(1, "Reading both.", null)),
                emit(new LlmStreamEvent.ToolCallStart(2, "call-1", "read", Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(2, "{\"path\":\"file.txt\"}", false)),
                emit(new LlmStreamEvent.ToolCallStart(3, "call-2", "bash", Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(3, "{\"command\":\"ls -la\"}", false)),
                emit(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.tokens(120, 40), "resp-default"))));
    }

    /// **2.** One text block, `Done(STOP)`. Pins the minimal turn: `STOP` is not promoted to a
    /// tool-use turn and no `ToolStart` is ever emitted.
    public static ScriptedProvider simpleText() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.TextStart(0)),
                emit(new LlmStreamEvent.TextDelta(0, "Hello, world!")),
                emit(new LlmStreamEvent.TextEnd(0, "Hello, world!", null)),
                emit(new LlmStreamEvent.Done(StopReason.STOP, Usage.tokens(10, 5), "resp-simple"))));
    }

    /// **3.** Thinking → text → one `read` call. Pins block-order finalisation: THINK, TEXT, TOOL,
    /// with the tool call appended after the two closed blocks however the stream interleaved them.
    public static ScriptedProvider thinkingTextTool() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.ThinkingStart(0)),
                emit(new LlmStreamEvent.ThinkingDelta(0, "Check the file.")),
                emit(new LlmStreamEvent.ThinkingEnd(0, "Check the file.", "think-sig", false)),
                emit(new LlmStreamEvent.TextStart(1)),
                emit(new LlmStreamEvent.TextDelta(1, "Looking now.")),
                emit(new LlmStreamEvent.TextEnd(1, "Looking now.", null)),
                emit(new LlmStreamEvent.ToolCallStart(2, "call-1", "read", Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(2, "{\"path\":\"file.txt\"}", false)),
                emit(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.tokens(90, 30), "resp-ttt"))));
    }

    /// **4.** Two retryable open failures, then the happy text turn. Pins that provider-side retry
    /// is invisible to the core: exactly one `TurnStart` and one assistant message result.
    public static ScriptedProvider retries() {
        var steps = new ArrayList<Step>();
        steps.add(failOpen(new IllegalStateException("transient upstream failure 1"), true));
        steps.add(failOpen(new IllegalStateException("transient upstream failure 2"), true));
        steps.addAll(simpleText().script());
        return of(steps);
    }

    /// **5.** Retryable open failure on every attempt. Pins `RunOutcome.Failed` and an
    /// `AssistantMessage` with `stopReason == ERROR` — a failure is data, never a thrown run.
    public static ScriptedProvider retryExhausted() {
        return of(List.of(
                failOpen(new IllegalStateException("upstream unavailable"), true),
                failOpen(new IllegalStateException("upstream unavailable"), true),
                failOpen(new IllegalStateException("upstream unavailable"), true)));
    }

    /// **6.** A non-retryable open failure. Pins that no attempt is burned beyond the first.
    public static ScriptedProvider nonRetryable() {
        return of(List.of(failOpen(new IllegalArgumentException("model not found: nope-1"), false)));
    }

    /// **7.** Text, then `Failed(ERROR)` with **no `Done`**. Pins mid-stream failure: the partial
    /// text is still finalised into exactly one `MessageEnd`.
    public static ScriptedProvider streamError() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.TextStart(0)),
                emit(new LlmStreamEvent.TextDelta(0, "Before error")),
                emit(new LlmStreamEvent.TextEnd(0, "Before error", null)),
                emit(new LlmStreamEvent.Failed(StopReason.ERROR, "upstream closed the connection"))));
    }

    /// **8.** One call to a name no registry holds. Pins `Tool {name} not found` / `TOOL_NOT_FOUND`
    /// and that I5/I6 hold for a call that never reaches a tool.
    public static ScriptedProvider unknownTool() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.ToolCallStart(0, "call-1", "unknown_tool", Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(0, "{\"whatever\":1}", false)),
                emit(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.tokens(20, 8), "resp-unknown"))));
    }

    /// **9.** Six text deltas. Pins delta accumulation and exactly one `TextEnd`.
    public static ScriptedProvider longText() {
        var steps = new ArrayList<Step>();
        steps.add(emit(new LlmStreamEvent.Start()));
        steps.add(emit(new LlmStreamEvent.TextStart(0)));
        var text = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            String piece = "chunk " + i + " ";
            text.append(piece);
            steps.add(emit(new LlmStreamEvent.TextDelta(0, piece)));
        }
        steps.add(emit(new LlmStreamEvent.TextEnd(0, text.toString(), null)));
        steps.add(emit(new LlmStreamEvent.Done(StopReason.STOP, Usage.tokens(30, 60), "resp-long")));
        return of(steps);
    }

    /// **10.** A `read` call whose arguments are **complete**, then a hang. Pins that a stall is a
    /// failed turn: the call is kept for diagnosis but never executed.
    public static ScriptedProvider toolHang() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.TextStart(0)),
                emit(new LlmStreamEvent.TextDelta(0, "Reading.")),
                emit(new LlmStreamEvent.TextEnd(0, "Reading.", null)),
                emit(new LlmStreamEvent.ToolCallStart(1, "call-1", "read", Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(1, "{\"path\":\"file.txt\"}", false)),
                hang()));
    }

    /// **11.** A `write` call cut off mid-JSON, then a hang, with **no** start snapshot. Pins that
    /// nothing executes and the call carries `Json.Null` arguments.
    public static ScriptedProvider toolHangInvalidJson() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.ToolCallStart(0, "call-1", "write", Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(0, "{\"path\": \"/tmp/test.txt\", \"content\": \"incomplete", false)),
                hang()));
    }

    /// **12. The stale-snapshot trap.** A `write` whose `initialArguments` name `/tmp/stale.txt`,
    /// then a truncated `replace = true` delta, then a hang. Pins that received fragments are
    /// authoritative: malformed JSON never falls back to the snapshot, so the stale path is never written.
    public static ScriptedProvider toolHangWithInitialArgs() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.ToolCallStart(0, "call-1", "write",
                        Json.obj("path", Json.str("/tmp/stale.txt"), "content", Json.str("stale snapshot")))),
                emit(new LlmStreamEvent.ToolCallDelta(0, "{\"path\": \"/tmp/fresh.txt\", \"content\": \"replace", true)),
                hang()));
    }

    /// **13.** A `bash` call assembled from 24 small fragments. Pins assembler correctness across
    /// many deltas — nothing but end of stream, a stall or a cancel flushes the accumulator.
    public static ScriptedProvider toolWithManyChunks() {
        String arguments = "{\"command\":\"echo hello from twenty four chunks\"}";
        var steps = new ArrayList<Step>();
        steps.add(emit(new LlmStreamEvent.Start()));
        steps.add(emit(new LlmStreamEvent.ToolCallStart(0, "call-1", "bash", Json.Obj.EMPTY)));
        for (String fragment : split(arguments, 24)) {
            steps.add(emit(new LlmStreamEvent.ToolCallDelta(0, fragment, false)));
        }
        steps.add(emit(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.tokens(55, 25), "resp-chunks")));
        return of(steps);
    }

    /// **14.** Whitespace-only text, then thinking, then real text. Pins that a blank text block
    /// never opens a TEXT block in the finalised message.
    public static ScriptedProvider leadingEmptyTextThenThink() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.TextStart(0)),
                emit(new LlmStreamEvent.TextDelta(0, "\n\n")),
                emit(new LlmStreamEvent.TextEnd(0, "\n\n", null)),
                emit(new LlmStreamEvent.ThinkingStart(1)),
                emit(new LlmStreamEvent.ThinkingDelta(1, "Right.")),
                emit(new LlmStreamEvent.ThinkingEnd(1, "Right.", "think-sig", false)),
                emit(new LlmStreamEvent.TextStart(2)),
                emit(new LlmStreamEvent.TextDelta(2, "Hello, world!")),
                emit(new LlmStreamEvent.TextEnd(2, "Hello, world!", null)),
                emit(new LlmStreamEvent.Done(StopReason.STOP, Usage.tokens(12, 6), "resp-blank-think"))));
    }

    /// **15.** Whitespace-only text, then real text. Same rule, and the real block still opens
    /// cleanly at index 1.
    public static ScriptedProvider leadingEmptyTextThenText() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.TextStart(0)),
                emit(new LlmStreamEvent.TextDelta(0, "\n\n")),
                emit(new LlmStreamEvent.TextEnd(0, "\n\n", null)),
                emit(new LlmStreamEvent.TextStart(1)),
                emit(new LlmStreamEvent.TextDelta(1, "Hello, world!")),
                emit(new LlmStreamEvent.TextEnd(1, "Hello, world!", null)),
                emit(new LlmStreamEvent.Done(StopReason.STOP, Usage.tokens(12, 6), "resp-blank-text"))));
    }

    /// **16.** A normal turn whose `Done` carries usage above a typical `contextWindow - reserved`.
    /// Drives a compaction trigger.
    public static ScriptedProvider overflowThenStop() { return of(overflowScript()); }

    /// **17.** Scenario 16, and then the follow-up request (the summary) fails on open. Pins that
    /// a failed compaction must never be reported as a success.
    public static ScriptedProvider compactionFails() {
        return sequence(List.of(
                overflowScript(),
                List.of(failOpen(new IllegalStateException("summary request failed"), false))));
    }

    /// **18.** A tool-call delta, then a whole text block, then the **rest** of the same call's
    /// arguments. Pins that a content-block transition must not flush the argument accumulator.
    public static ScriptedProvider interleavedTextInToolArgs() {
        return of(List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.ToolCallStart(0, "call-1", "write", Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(0, "{\"path\":\"/tmp/x.txt\",", false)),
                emit(new LlmStreamEvent.TextStart(1)),
                emit(new LlmStreamEvent.TextDelta(1, "writing it now")),
                emit(new LlmStreamEvent.TextEnd(1, "writing it now", null)),
                emit(new LlmStreamEvent.ToolCallDelta(0, "\"content\":\"hi\"}", false)),
                emit(new LlmStreamEvent.Done(StopReason.TOOL_USE, Usage.tokens(44, 18), "resp-interleaved"))));
    }

    /// The `Usage` scenario 16 reports: an input count that overruns a 200k window less a reserve.
    public static final Usage OVERFLOW_USAGE = new Usage(196_000, 3_000, 0, 0, 199_000, Usage.Cost.ZERO);

    private static List<Step> overflowScript() {
        return List.of(
                emit(new LlmStreamEvent.Start()),
                emit(new LlmStreamEvent.TextStart(0)),
                emit(new LlmStreamEvent.TextDelta(0, "That is everything I have room for.")),
                emit(new LlmStreamEvent.TextEnd(0, "That is everything I have room for.", null)),
                emit(new LlmStreamEvent.Done(StopReason.STOP, OVERFLOW_USAGE, "resp-overflow")));
    }

    /// A tool call built by hand, for scripts a test assembles itself.
    public static List<Step> toolCall(int index, String id, String name, String argumentsJson) {
        return List.of(
                emit(new LlmStreamEvent.ToolCallStart(index, id, name, Json.Obj.EMPTY)),
                emit(new LlmStreamEvent.ToolCallDelta(index, argumentsJson, false)));
    }

    /// A finished tool call delivered whole, as a provider that assembles server-side would.
    public static Step toolCallEnd(int index, ContentBlock.ToolCall call) {
        return emit(new LlmStreamEvent.ToolCallEnd(index, call));
    }

    private static List<String> split(String text, int pieces) {
        var out = new ArrayList<String>(pieces);
        int size = Math.max(1, text.length() / pieces);
        int at = 0;
        for (int i = 0; i < pieces - 1 && at < text.length(); i++) {
            int end = Math.min(text.length(), at + size);
            out.add(text.substring(at, end));
            at = end;
        }
        if (at < text.length()) out.add(text.substring(at));
        return out;
    }
}
