package sdk.agent;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import sdk.agent.concurrent.RunScope;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.TurnContext;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.Message;
import sdk.agent.message.MessageConverter;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStream;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.turn.Need;
import sdk.agent.turn.StepInput;
import sdk.agent.turn.StepOutcome;
import sdk.agent.turn.TurnMachine;
import sdk.agent.turn.TurnPhase;
import sdk.agent.turn.TurnState;

/// Layer 2: owns the I/O, satisfies every [Need] of the pure [TurnMachine], and returns only at
/// durable checkpoints. One `advance()` performs exactly one row of the phase table:
///
/// | from | work | to |
/// |---|---|---|
/// | `NEW` | `RunStart`; append prompts (+ steering unless skipped) | `TURN_OPENING` |
/// | `TURN_OPENING` | ck1; `beforeTurn`; inject; ck2; transform → convert → request → `beforeRequest`; stream to a terminal event | `ASSISTANT_READY` |
/// | `ASSISTANT_READY` | `afterAssistant` verdict | `FINISHED` / `TURN_OPENING` / `TOOLS_RUNNING` / `TURN_CLOSED` |
/// | `TOOLS_RUNNING` | ck3; tool cap; one batch through the funnel | `TOOLS_RUNNING` or `TURN_CLOSED` |
/// | `TURN_CLOSED` | ck4; drain steering | `TURN_OPENING` if steering or the turn had calls, else `FOLLOW_UP` |
/// | `FOLLOW_UP` | ck5; drain follow-ups | `TURN_OPENING` or `FINISHED` |
/// | `FINISHED` | `RunEnd` (idempotent) | `Step.Done` |
///
/// A `RunEngine` instance serves exactly one run: the `RunEnd` guard is its only mutable field,
/// and it fires from the `FINISHED` row **and** from the catch in [#advance], so a subscriber sees
/// `RunEnd(produced, Failed(…))` for an uncaught throw exactly as it sees a clean finish.
public final class RunEngine {

    private static final System.Logger LOG = System.getLogger(RunEngine.class.getName());

    private final TurnMachine machine;
    private final AtomicBoolean runEndEmitted = new AtomicBoolean();

    public RunEngine() { this(TurnMachine.standard()); }

    public RunEngine(TurnMachine machine) { this.machine = Objects.requireNonNull(machine, "machine"); }

    public static RunState start(List<AgentMessage> prompts, List<AgentMessage> seed, RunOptions options, String runId, Instant now) {
        Objects.requireNonNull(options, "options");
        return new RunState(runId, Phase.NEW, 0, seed, List.of(), prompts, null, options.limits(), 0, 0, null, now,
                options.skipInitialSteeringPoll(), null, RunState.SCHEMA_VERSION, options.toolSetHash());
    }

    /// Performs exactly one row. Binds [RunScope#CURRENT] for the duration, so tools may read it.
    public Step advance(RunState s, RunDeps d) {
        Objects.requireNonNull(s, "state");
        Objects.requireNonNull(d, "deps");
        var scope = new RunScope(s.runId(), s.turnIndex(), d.cancel());
        try {
            return ScopedValue.where(RunScope.CURRENT, scope).call(() -> row(s, d));
        } catch (Throwable t) {
            emitRunEnd(s, new RunOutcome.Failed(StopReason.ERROR, ToolFunnel.describe(t), t), d);
            throw t;
        }
    }

    /// Convenience loop; `advance()` owns the guard.
    public RunResult run(RunState s, RunDeps d) {
        for (Step step = advance(s, d); ; step = advance(step.state(), d)) {
            if (step instanceof Step.Done done) return new RunResult(done.state(), done.outcome());
        }
    }

    // ---- the rows -----------------------------------------------------------------------------

    private Step row(RunState s, RunDeps d) {
        return switch (s.phase()) {
            case NEW             -> begin(s, d);
            case TURN_OPENING    -> openTurn(s, d);
            case ASSISTANT_READY -> judge(s, d);
            case TOOLS_RUNNING   -> runTools(s, d);
            case TURN_CLOSED     -> closeTurn(s, d);
            case FOLLOW_UP       -> followUp(s, d);
            case FINISHED        -> finish(s, d);
        };
    }

    private Step begin(RunState s, RunDeps d) {
        var b = s.toBuilder();
        emit(new AgentEvent.RunStart(s.runId(), AgentEvent.RUN_SCOPED, now(d)), d);
        var inject = new ArrayList<>(s.pendingInjection());
        b.pendingInjection.clear();
        if (!s.skipInitialSteeringPoll()) inject.addAll(d.steering().get());
        for (AgentMessage m : inject) emitAndAppend(b, m, AgentEvent.RUN_SCOPED, d);
        b.phase = Phase.TURN_OPENING;
        return new Step.Continue(b.build());
    }

    private Step openTurn(RunState s, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(s, d);                              // ck1
        if (tripped.isPresent()) return abort(s, d, tripped.get());

        var b = s.toBuilder();
        var injected = new ArrayList<>(s.pendingInjection());
        b.pendingInjection.clear();
        TurnContext ctx = context(s, d);
        injected.addAll(safely(() -> d.hooks().beforeTurn(ctx), List.of()));
        for (AgentMessage m : injected) emitAndAppend(b, m, s.turnIndex(), d);

        RunState opened = b.build();
        tripped = checkpoint(opened, d);                                               // ck2
        if (tripped.isPresent()) return abort(opened, d, tripped.get());

        TurnContext ctx2 = context(opened, d);
        List<AgentMessage> transformed = safely(() -> d.hooks().transformContext(opened.transcript(), d.cancel()), opened.transcript());
        List<Message> converted = safely(() -> d.converter().toLlm(transformed), MessageConverter.DEFAULT.toLlm(transformed));
        LlmRequest built = d.requestTemplate().withMessages(converted).withTools(d.tools().specs());
        LlmRequest request = safely(() -> d.hooks().beforeRequest(built, ctx2), built);

        b = opened.toBuilder();
        TurnState turn = drive(TurnState.opening(s.runId(), s.turnIndex(), request.model()), request, b, d);
        b.turn = turn;
        b.turnsUsed++;
        b.usage = b.usage.plus(turn.assistant().usage());
        b.phase = Phase.ASSISTANT_READY;
        return new Step.Continue(b.build());
    }

    /// Drives one turn from `Begin` to `ASSISTANT_READY`, satisfying `Need.Stream` and `Need.Chunk`.
    private TurnState drive(TurnState turn, LlmRequest request, RunState.Builder b, RunDeps d) {
        StepOutcome out = step(turn, new StepInput.Begin(request), b, d);
        turn = out.state();

        LlmStream stream;
        try {
            stream = d.provider().stream(request, d.cancel());
        } catch (IOException | RuntimeException e) {
            return step(turn, new StepInput.StreamFailed(ToolFunnel.describe(e), false), b, d).state();
        }
        if (stream == null) return step(turn, new StepInput.StreamFailed("provider returned no stream", false), b, d).state();

        try (var pump = new ProviderPump(stream, d.cancel())) {
            out = step(turn, new StepInput.StreamOpened(), b, d);
            turn = out.state();
            while (turn.phase() == TurnPhase.STREAMING) {
                if (d.cancel().isCancelled()) {
                    turn = step(turn, new StepInput.Cancel(), b, d).state();
                    break;
                }
                OptionalLong idle = out instanceof StepOutcome.Needs(_, _, Need.Chunk(var millis)) ? millis : OptionalLong.empty();
                StepInput input;
                try {
                    LlmStreamEvent event = pump.next(idle);
                    input = event == null ? new StepInput.StreamExhausted() : new StepInput.Chunk(event);
                } catch (TimeoutException _) {
                    input = new StepInput.IdleTimedOut();
                } catch (IOException | RuntimeException e) {          // a provider bug is still one failure path
                    input = new StepInput.StreamFailed(ToolFunnel.describe(e), false);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    input = new StepInput.Cancel();
                }
                out = step(turn, input, b, d);
                turn = out.state();
            }
        }
        return turn;
    }

    private Step judge(RunState s, RunDeps d) {
        var b = s.toBuilder();
        TurnState turn = s.turn();
        AssistantMessage assistant = turn.assistant();
        TurnContext ctx = context(s, d);
        TurnVerdict verdict = safely(() -> d.hooks().afterAssistant(assistant, ctx), TurnVerdict.PROCEED);
        switch (verdict) {
            case TurnVerdict.Stop(var outcome, var message) -> {
                if (message != null) emitAndAppend(b, message, s.turnIndex(), d);
                b.turn = step(turn, new StepInput.Verdict(verdict), b, d).state();
                b.outcome = outcome;
                b.phase = Phase.FINISHED;
            }
            case TurnVerdict.Retry(var message, var reason) -> {
                LOG.log(System.Logger.Level.DEBUG, "turn {0} retried: {1}", s.turnIndex(), reason);
                b.turn = step(turn, new StepInput.Verdict(verdict), b, d).state();
                b.pendingInjection.add(message);
                b.turnIndex++;
                b.phase = Phase.TURN_OPENING;
            }
            case TurnVerdict.Proceed _ -> {
                StepOutcome out = step(turn, new StepInput.Verdict(verdict), b, d);
                b.turn = out.state();
                b.phase = out instanceof StepOutcome.Finished ? Phase.TURN_CLOSED : Phase.TOOLS_RUNNING;
            }
        }
        return new Step.Continue(b.build());
    }

    private Step runTools(RunState s, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(s, d);                              // ck3
        if (tripped.isPresent()) return abort(s, d, tripped.get());

        TurnState turn = s.turn();
        List<ContentBlock.ToolCall> batch = firstBatch(turn.pendingCalls(), s.limits().toolExecution(), d.tools());
        int cap = s.limits().maxToolCalls();
        if (s.toolCallsUsed() + batch.size() > cap) {
            return abort(s, d, new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TOOL_CALLS, "tool call cap reached: " + cap));
        }

        var b = s.toBuilder();
        var funnel = new ToolFunnel(d, s, context(s, d), e -> apply(b, List.of(e), d));
        List<ToolResultMessage> results;
        try {
            results = funnel.runBatch(batch, batch.size() > 1);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return abort(b.build(), d, new RunOutcome.Aborted());
        }
        for (ToolResultMessage r : results) turn = step(turn, new StepInput.ToolSettled(r.toolCallId(), r), b, d).state();
        b.turn = turn;
        b.toolCallsUsed += batch.size();
        b.phase = turn.phase() == TurnPhase.CLOSED ? Phase.TURN_CLOSED : Phase.TOOLS_RUNNING;
        return new Step.Continue(b.build());
    }

    private Step closeTurn(RunState s, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(s, d);                              // ck4
        if (tripped.isPresent()) return abort(s, d, tripped.get());

        var b = s.toBuilder();
        List<AgentMessage> steering = d.steering().get();
        b.pendingInjection.addAll(steering);
        boolean anotherTurn = !steering.isEmpty() || !s.turn().calls().isEmpty();
        if (anotherTurn) {
            b.turnIndex++;
            b.phase = Phase.TURN_OPENING;
        } else {
            b.phase = Phase.FOLLOW_UP;
        }
        return new Step.Continue(b.build());
    }

    private Step followUp(RunState s, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(s, d);                              // ck5
        if (tripped.isPresent()) return abort(s, d, tripped.get());

        var b = s.toBuilder();
        List<AgentMessage> followUps = d.followUps().get();
        if (!followUps.isEmpty()) {
            b.pendingInjection.addAll(followUps);
            b.turnIndex++;
            b.phase = Phase.TURN_OPENING;
        } else {
            b.outcome = outcomeOf(s.turn().assistant());
            b.phase = Phase.FINISHED;
        }
        return new Step.Continue(b.build());
    }

    private Step finish(RunState s, RunDeps d) {
        RunOutcome outcome = s.outcome() != null ? s.outcome() : new RunOutcome.Completed(StopReason.STOP);
        emitRunEnd(s, outcome, d);
        return new Step.Done(s, outcome);
    }

    // ---- abort and checkpoints ----------------------------------------------------------------

    /// The abort path: an open turn is closed through the machine (unfilled slots padded), then
    /// the run finishes with `outcome`. Open `ToolStart`s were already completed by the funnel.
    private Step abort(RunState s, RunDeps d, RunOutcome outcome) {
        var b = s.toBuilder();
        if (s.turnOpen()) b.turn = step(s.turn(), new StepInput.Cancel(), b, d).state();
        b.outcome = outcome;
        b.phase = Phase.FINISHED;
        return new Step.Continue(b.build());
    }

    private static Optional<RunOutcome> checkpoint(RunState s, RunDeps d) {
        if (d.cancel().isCancelled()) return Optional.of(new RunOutcome.Aborted());
        return s.limits().wallClock()
                .filter(limit -> now(d).isAfter(s.startedAt().plus(limit)))
                .map(limit -> new RunOutcome.LimitExceeded(RunOutcome.Limit.WALL_CLOCK, "wall clock exceeded: " + limit));
    }

    private static RunOutcome outcomeOf(AssistantMessage last) {
        if (last == null) return new RunOutcome.Completed(StopReason.STOP);
        return switch (last.stopReason()) {
            case ABORTED -> new RunOutcome.Aborted();
            case ERROR   -> new RunOutcome.Failed(StopReason.ERROR, String.valueOf(last.errorMessage()), null);
            default      -> new RunOutcome.Completed(last.stopReason());
        };
    }

    /// The batching rule: `SEQUENTIAL` pins every batch to one call; otherwise a batch is a single
    /// mutating/unknown call or a maximal contiguous run of `READ_ONLY` calls, in source order.
    static List<ContentBlock.ToolCall> firstBatch(List<ContentBlock.ToolCall> pending, ToolExecutionMode mode, ToolRegistry tools) {
        if (pending.isEmpty()) return List.of();
        if (mode == ToolExecutionMode.SEQUENTIAL || !readOnly(pending.getFirst(), tools)) return List.of(pending.getFirst());
        int n = 1;
        while (n < pending.size() && readOnly(pending.get(n), tools)) n++;
        return pending.subList(0, n);
    }

    private static boolean readOnly(ContentBlock.ToolCall call, ToolRegistry tools) {
        return tools.resolve(call.name()).map(t -> t.kind() == ToolKind.READ_ONLY).orElse(false);
    }

    // ---- events ------------------------------------------------------------------------------

    /// I2: idempotent by construction, so the `FINISHED` row and the catch in `advance` cannot both fire.
    private void emitRunEnd(RunState s, RunOutcome outcome, RunDeps d) {
        if (!runEndEmitted.compareAndSet(false, true)) return;
        emit(new AgentEvent.RunEnd(s.runId(), AgentEvent.RUN_SCOPED, now(d), s.produced(), outcome), d);
    }

    private StepOutcome step(TurnState turn, StepInput input, RunState.Builder b, RunDeps d) {
        StepOutcome out = machine.step(turn, input, now(d));
        if (out instanceof StepOutcome.Ignored(var reason, _)) LOG.log(System.Logger.Level.DEBUG, "turn machine ignored input: {0}", reason);
        apply(b, out.events(), d);
        return out;
    }

    /// The transcript grows **only** at `MessageEnd`, and only here.
    private void apply(RunState.Builder b, List<AgentEvent> events, RunDeps d) {
        for (AgentEvent e : events) {
            emit(e, d);
            if (e instanceof AgentEvent.MessageEnd(_, _, _, var message)) {
                b.transcript.add(message);
                b.produced.add(message);
            }
        }
    }

    private void emitAndAppend(RunState.Builder b, AgentMessage m, int turnIndex, RunDeps d) {
        Instant at = now(d);
        apply(b, List.of(new AgentEvent.MessageStart(b.runId, turnIndex, at, m), new AgentEvent.MessageEnd(b.runId, turnIndex, at, m)), d);
    }

    private static void emit(AgentEvent e, RunDeps d) {
        d.sink().emit(e);
        try {
            d.hooks().onEvent(e);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "hook onEvent threw", t);
        }
    }

    private static TurnContext context(RunState s, RunDeps d) {
        return new TurnContext(s.runId(), s.turnIndex(), s.transcript(), s.limits(), s.turnsUsed(), d.tools(), d.cancel(), d.clock());
    }

    private static <T> T safely(Supplier<T> call, T fallback) {
        try {
            T value = call.get();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "hook threw; using fallback", t);
            return fallback;
        }
    }

    private static Instant now(RunDeps d) { return d.clock().instant(); }
}
