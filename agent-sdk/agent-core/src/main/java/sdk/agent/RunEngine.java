package sdk.agent;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import sdk.agent.concurrent.Fork;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.TurnContext;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.LlmStreamEvent;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.turn.Need;
import sdk.agent.turn.StepInput;
import sdk.agent.turn.StepOutcome;
import sdk.agent.turn.TurnMachine;
import sdk.agent.turn.TurnPhase;
import sdk.agent.turn.TurnState;

/// The driver: owns the I/O, satisfies every [Need] of the pure [TurnMachine], and returns only at
/// durable checkpoints. One `advance()` performs exactly one row of the phase table:
///
/// | from | work | to |
/// |---|---|---|
/// | `NEW` | `RunStart`; append prompts and queued steering | `TURN_OPENING` |
/// | `TURN_OPENING` | ck1; `beforeTurn`; inject; ck2; transform → convert → request → `beforeRequest`; stream to a terminal event | `ASSISTANT_READY` |
/// | `ASSISTANT_READY` | `afterAssistant` verdict | `FINISHED` / `TURN_OPENING` / `TOOLS_RUNNING` / `TURN_CLOSED` |
/// | `TOOLS_RUNNING` | ck3; tool cap; one batch through the funnel | `TOOLS_RUNNING` or `TURN_CLOSED` |
/// | `TURN_CLOSED` | ck4; drain steering | `TURN_OPENING` if steering or the turn had calls, else `FOLLOW_UP` |
/// | `FOLLOW_UP` | ck5; drain follow-ups | `TURN_OPENING` or `FINISHED` |
/// | `FINISHED` | `RunEnd` (idempotent) | `Step.Done` |
///
/// A `RunEngine` instance serves exactly one run. Ordinary collaborator failures finalize the
/// current state; JVM-fatal errors are not recoverable. `RunEnd` is emitted at most once.
public final class RunEngine {

    private static final System.Logger LOG = System.getLogger(RunEngine.class.getName());

    private final TurnMachine machine;
    private final AtomicBoolean runEndEmitted = new AtomicBoolean();

    public RunEngine() { this(TurnMachine.standard()); }

    public RunEngine(TurnMachine machine) { this.machine = Objects.requireNonNull(machine, "machine"); }

    /// The initial state: `seed` is the transcript inherited from earlier runs, `prompts` are appended first.
    public static RunState start(List<AgentMessage> prompts, List<AgentMessage> seed, RunLimits limits,
                                 String toolSetHash, String runId, Instant now) {
        return new RunState(runId, Phase.NEW, 0, seed, seed.size(), prompts, null, limits, 0, 0, null, now, null, toolSetHash);
    }

    /// Performs exactly one row.
    public Step advance(RunState s, RunDeps d) {
        Objects.requireNonNull(s, "state");
        Objects.requireNonNull(d, "deps");
        var b = s.toBuilder();
        try (var _ = d.cancel().interruptOnCancel(Thread.currentThread());
             var timer = b.limits.wallClock().isPresent() ? Fork.open() : null) {
            if (timer != null) {
                Duration remaining = Duration.between(now(d), b.startedAt.plus(b.limits.wallClock().orElseThrow()));
                timer.fork("run-deadline", () -> {
                    if (remaining.isPositive()) Thread.sleep(remaining);
                    d.cancel().cancel();
                    return null;
                });
            }
            Step result = row(b, d);
            if (!(result instanceof Step.Done)) {
                Optional<RunOutcome> tripped = checkpoint(b, d);
                if (tripped.isPresent()) return abort(b, d, tripped.get());
            }
            return result;
        } catch (RuntimeException failure) {
            abort(b, d, checkpoint(b, d).orElseGet(() ->
                    new RunOutcome.Failed(StopReason.ERROR, ToolFunnel.describe(failure), failure)));
            return finish(b, d);
        } finally {
            if (d.cancel().isCancelled()) Thread.interrupted();
        }
    }

    /// Convenience loop; `advance()` owns the guard.
    public RunResult run(RunState s, RunDeps d) {
        for (Step step = advance(s, d); ; step = advance(step.state(), d)) {
            if (step instanceof Step.Done done) return new RunResult(done.state(), done.outcome());
        }
    }

    // ---- the rows -----------------------------------------------------------------------------

    private Step row(RunState.Builder b, RunDeps d) {
        return switch (b.phase) {
            case NEW             -> begin(b, d);
            case TURN_OPENING    -> openTurn(b, d);
            case ASSISTANT_READY -> judge(b, d);
            case TOOLS_RUNNING   -> runTools(b, d);
            case TURN_CLOSED     -> closeTurn(b, d);
            case FOLLOW_UP       -> followUp(b, d);
            case FINISHED        -> finish(b, d);
        };
    }

    private Step begin(RunState.Builder b, RunDeps d) {
        emit(new AgentEvent.RunStart(b.runId, AgentEvent.RUN_SCOPED, now(d)), d);
        var inject = new ArrayList<>(b.pendingInjection);
        b.pendingInjection.clear();
        inject.addAll(d.steering().get());
        for (AgentMessage m : inject) emitAndAppend(b, m, AgentEvent.RUN_SCOPED, d);
        b.phase = Phase.TURN_OPENING;
        return new Step.Continue(b.build());
    }

    private Step openTurn(RunState.Builder b, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(b, d);
        if (tripped.isPresent()) return abort(b, d, tripped.get());
        if (b.turnsUsed >= b.limits.maxTurns()) {
            return abort(b, d, new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TURNS,
                    "turn cap reached: " + b.limits.maxTurns()));
        }

        var injected = new ArrayList<>(b.pendingInjection);
        b.pendingInjection.clear();
        injected.addAll(Objects.requireNonNull(d.hooks().beforeTurn(context(b.build(), d)), "beforeTurn returned null"));
        for (AgentMessage m : injected) emitAndAppend(b, m, b.turnIndex, d);

        tripped = checkpoint(b, d);
        if (tripped.isPresent()) return abort(b, d, tripped.get());
        RunState opened = b.build();
        List<AgentMessage> transformed = Objects.requireNonNull(
                d.hooks().transformContext(opened.transcript(), d.cancel()), "transformContext returned null");
        var converted = Objects.requireNonNull(d.converter().toLlm(transformed), "converter returned null");
        LlmRequest built = d.requestTemplate().withMessages(converted).withTools(d.tools().specs());
        LlmRequest request = Objects.requireNonNull(d.hooks().beforeRequest(built, context(opened, d)),
                "beforeRequest returned null");

        b.turnsUsed++;
        TurnState turn = drive(TurnState.opening(b.runId, b.turnIndex, request.model()), request, b, d);
        b.usage = b.usage.plus(turn.assistant().usage());
        b.phase = Phase.ASSISTANT_READY;
        return new Step.Continue(b.build());
    }

    /// Drives one turn from `Begin` to `ASSISTANT_READY`, satisfying `Need.Stream` and `Need.Chunk`.
    private TurnState drive(TurnState turn, LlmRequest request, RunState.Builder b, RunDeps d) {
        StepOutcome out = step(turn, new StepInput.Begin(request), b, d);
        turn = out.state();

        try (var pump = new ProviderPump(d.provider(), request, d.cancel())) {
            try {
                pump.awaitOpen();
            } catch (IOException | RuntimeException e) {
                return step(turn, new StepInput.StreamFailed(ToolFunnel.describe(e), false), b, d).state();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return step(turn, new StepInput.Cancel(), b, d).state();
            }
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

    private Step judge(RunState.Builder b, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(b, d);
        if (tripped.isPresent()) return abort(b, d, tripped.get());
        TurnState turn = b.turn;
        AssistantMessage assistant = turn.assistant();
        if (assistant.stopReason() == StopReason.ERROR || assistant.stopReason() == StopReason.ABORTED) {
            return abort(b, d, outcomeOf(assistant));
        }
        TurnVerdict verdict = Objects.requireNonNull(
                d.hooks().afterAssistant(assistant, context(b.build(), d)), "afterAssistant returned null");
        switch (verdict) {
            case TurnVerdict.Stop(var outcome, var message) -> {
                step(turn, new StepInput.Verdict(verdict), b, d);
                if (message != null) emitAndAppend(b, message, b.turnIndex, d);
                b.outcome = outcome;
                b.phase = Phase.FINISHED;
            }
            case TurnVerdict.Retry(var message, var reason) -> {
                LOG.log(System.Logger.Level.DEBUG, "turn {0} retried: {1}", b.turnIndex, reason);
                step(turn, new StepInput.Verdict(verdict), b, d);
                b.pendingInjection.add(message);
                b.turnIndex++;
                b.phase = Phase.TURN_OPENING;
            }
            case TurnVerdict.Proceed _ -> {
                // A truncated turn never grants execution, even when the guard is disabled.
                StepOutcome out = step(turn, assistant.stopReason() == StopReason.LENGTH
                        ? new StepInput.Cancel() : new StepInput.Verdict(verdict), b, d);
                b.phase = out instanceof StepOutcome.Finished ? Phase.TURN_CLOSED : Phase.TOOLS_RUNNING;
            }
        }
        return new Step.Continue(b.build());
    }

    private Step runTools(RunState.Builder b, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(b, d);
        if (tripped.isPresent()) return abort(b, d, tripped.get());

        TurnState turn = b.turn;
        List<ContentBlock.ToolCall> batch = firstBatch(turn.pendingCalls(), b.limits.toolExecution(), d.tools());
        int cap = b.limits.maxToolCalls();
        if (batch.size() > cap - b.toolCallsUsed) {
            return abort(b, d, new RunOutcome.LimitExceeded(RunOutcome.Limit.MAX_TOOL_CALLS,
                    "tool call cap reached: " + cap));
        }

        RunState current = b.build();
        var funnel = new ToolFunnel(d, current, context(current, d), e -> apply(b, List.of(e), d));
        List<ToolResultMessage> results = funnel.runBatch(batch, batch.size() > 1);
        for (ToolResultMessage r : results) turn = step(turn, new StepInput.ToolSettled(r.toolCallId(), r), b, d).state();
        b.toolCallsUsed += batch.size();
        b.phase = turn.phase() == TurnPhase.CLOSED ? Phase.TURN_CLOSED : Phase.TOOLS_RUNNING;
        return new Step.Continue(b.build());
    }

    private Step closeTurn(RunState.Builder b, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(b, d);
        if (tripped.isPresent()) return abort(b, d, tripped.get());
        List<AgentMessage> steering = d.steering().get();
        b.pendingInjection.addAll(steering);
        if (!steering.isEmpty() || !b.turn.calls().isEmpty()) {
            b.turnIndex++;
            b.phase = Phase.TURN_OPENING;
        } else {
            b.phase = Phase.FOLLOW_UP;
        }
        return new Step.Continue(b.build());
    }

    private Step followUp(RunState.Builder b, RunDeps d) {
        Optional<RunOutcome> tripped = checkpoint(b, d);
        if (tripped.isPresent()) return abort(b, d, tripped.get());
        List<AgentMessage> followUps = d.followUps().get();
        if (!followUps.isEmpty()) {
            b.pendingInjection.addAll(followUps);
            b.turnIndex++;
            b.phase = Phase.TURN_OPENING;
        } else {
            b.outcome = outcomeOf(b.turn.assistant());
            b.phase = Phase.FINISHED;
        }
        return new Step.Continue(b.build());
    }

    private Step finish(RunState.Builder b, RunDeps d) {
        if (b.outcome == null) b.outcome = new RunOutcome.Completed(StopReason.STOP);
        RunState state = b.build();
        emitRunEnd(state, b.outcome, d);
        return new Step.Done(state, b.outcome);
    }

    // ---- abort and checkpoints ----------------------------------------------------------------

    /// Close the current turn before finishing; the funnel owns any announced ToolEnd.
    private Step abort(RunState.Builder b, RunDeps d, RunOutcome outcome) {
        if (b.turn != null && b.turn.phase() != TurnPhase.CLOSED) step(b.turn, new StepInput.Cancel(), b, d);
        b.outcome = outcome;
        b.phase = Phase.FINISHED;
        return new Step.Continue(b.build());
    }

    private static Optional<RunOutcome> checkpoint(RunState.Builder b, RunDeps d) {
        Optional<RunOutcome> expired = b.limits.wallClock()
                .filter(limit -> !now(d).isBefore(b.startedAt.plus(limit)))
                .map(limit -> new RunOutcome.LimitExceeded(RunOutcome.Limit.WALL_CLOCK, "wall clock exceeded: " + limit));
        return expired.isPresent() ? expired
                : d.cancel().isCancelled() ? Optional.of(new RunOutcome.Aborted()) : Optional.empty();
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

    /// Idempotent by construction, so the `FINISHED` row and the catch in `advance` cannot both fire.
    private void emitRunEnd(RunState s, RunOutcome outcome, RunDeps d) {
        if (!runEndEmitted.compareAndSet(false, true)) return;
        emit(new AgentEvent.RunEnd(s.runId(), AgentEvent.RUN_SCOPED, now(d), s.produced(), outcome), d);
    }

    private StepOutcome step(TurnState turn, StepInput input, RunState.Builder b, RunDeps d) {
        StepOutcome out = machine.step(turn, input, now(d));
        b.turn = out.state();
        if (out instanceof StepOutcome.Ignored(var reason, _)) LOG.log(System.Logger.Level.DEBUG, "turn machine ignored input: {0}", reason);
        apply(b, out.events(), d);
        return out;
    }

    /// The transcript grows **only** at `MessageEnd`, and only here.
    private void apply(RunState.Builder b, List<AgentEvent> events, RunDeps d) {
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.MessageEnd(_, _, _, var message)) b.transcript.add(message);
            emit(e, d);
        }
    }

    private void emitAndAppend(RunState.Builder b, AgentMessage m, int turnIndex, RunDeps d) {
        Instant at = now(d);
        apply(b, List.of(new AgentEvent.MessageStart(b.runId, turnIndex, at, m), new AgentEvent.MessageEnd(b.runId, turnIndex, at, m)), d);
    }

    private static void emit(AgentEvent e, RunDeps d) {
        try {
            d.sink().emit(e);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "event sink threw", failure);
        }
        try {
            d.hooks().onEvent(e);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "hook onEvent threw", t);
        }
    }

    private static TurnContext context(RunState s, RunDeps d) {
        return new TurnContext(s.runId(), s.turnIndex(), s.transcript(), s.limits(), s.turnsUsed(), d.tools(), d.cancel(), d.clock());
    }


    private static Instant now(RunDeps d) { return d.clock().instant(); }
}
