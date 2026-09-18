package sdk.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import sdk.agent.concurrent.Fork;
import sdk.agent.event.AgentEvent;
import sdk.agent.hook.AfterToolCall;
import sdk.agent.hook.BeforeToolCall;
import sdk.agent.hook.ToolDecision;
import sdk.agent.hook.TurnContext;
import sdk.agent.json.ArgumentException;
import sdk.agent.json.Json;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolFailure;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolMessages;
import sdk.agent.tool.ToolResult;

/// The tool-call funnel: one exit point per call. Every call — unknown, not advertised this turn,
/// blocked, unparseable, crashed, cancelled or successful — leaves through [#emitOutcome], so a
/// `ToolEnd` and a `ToolResultMessage` are structurally guaranteed for every `ToolStart`.
///
/// A batch runs in three phases: announce + prepare **sequentially in source order** (so a
/// permission prompt cannot race), launch every runnable call on its own virtual thread, then
/// drain, filter and emit **strictly by index**. The hooks fail closed: a permission hook that
/// throws is `HOOK_FAILED`, never `ALLOW`; an output filter that throws replaces the result, never
/// leaks the unfiltered one.
final class ToolFunnel {

    /// Stage 1 either resolves to a result already or to a bound, runnable pair.
    private sealed interface Prepare {
        record Immediate(ToolResult result) implements Prepare { }
        record Prepared<P>(Tool<P> tool, P bound, Json arguments) implements Prepare { }
    }

    private static final ToolResult CANCELLED = ToolResult.error(ErrorKind.CANCELLED, ToolMessages.CANCELLED);

    private final RunDeps deps;
    private final RunState run;
    private final TurnContext ctx;
    private final AssistantMessage assistant;
    private final Consumer<AgentEvent> emit;
    private boolean open = true;                                    // progress gate, guarded by this

    ToolFunnel(RunDeps deps, RunState run, TurnContext ctx, Consumer<AgentEvent> emit) {
        this.deps = deps;
        this.run = run;
        this.ctx = ctx;
        this.assistant = run.turn().assistant();
        this.emit = emit;
    }

    /// Results are index-aligned with `batch`.
    List<ToolResultMessage> runBatch(List<ContentBlock.ToolCall> batch) {
        int n = batch.size();
        var prepared = new ArrayList<Prepare>(n);
        for (ContentBlock.ToolCall call : batch) {                                  // phase 1
            emit.accept(new AgentEvent.ToolStart(run.runId(), run.turnIndex(), now(), call.id(), call.name(), call.arguments()));
            prepared.add(deps.cancel().isCancelled() ? new Prepare.Immediate(CANCELLED) : prepare(call));
        }

        List<ToolResult> results = new ArrayList<>(Collections.nCopies(n, null));
        try (var fork = Fork.open(); var _ = deps.cancel().onCancel(fork::cancelAll)) {   // phase 2
            List<Fork.Handle<ToolResult>> handles = new ArrayList<>(Collections.nCopies(n, null));
            for (int i = 0; i < n; i++) {
                switch (prepared.get(i)) {
                    case Prepare.Immediate(var r) -> results.set(i, r);
                    case Prepare.Prepared<?> p   -> { var call = batch.get(i); handles.set(i, fork.fork(call.name() + "#" + call.id(), () -> execute(p, call))); }
                }
            }
            for (int i = 0; i < n; i++) if (results.get(i) == null) results.set(i, await(handles.get(i)));
            seal();
            if (deps.cancel().isCancelled()) Thread.interrupted();               // our own token raised it; clear it
        }

        var out = new ArrayList<ToolResultMessage>(n);
        for (int i = 0; i < n; i++) {                                               // phase 3, by index
            ContentBlock.ToolCall call = batch.get(i);
            ToolResult result = results.get(i);
            if (prepared.get(i) instanceof Prepare.Prepared<?> p) result = applyAfterToolCall(call, p.arguments(), result);
            out.add(emitOutcome(call, result));
        }
        return out;
    }

    // ---- stage 1 -----------------------------------------------------------------------------

    private Prepare prepare(ContentBlock.ToolCall call) {
        Optional<Tool<?>> tool = deps.tools().resolve(call.name());
        if (tool.isEmpty()) return immediate(ErrorKind.TOOL_NOT_FOUND, ToolMessages.TOOL_NOT_FOUND.formatted(call.name()));
        if (!run.turn().allowedTools().contains(call.name())) return immediate(ErrorKind.BLOCKED, ToolMessages.NOT_ADVERTISED.formatted(call.name()));
        if (call.arguments() == Json.Null.NULL) return immediate(ErrorKind.INVALID_ARGUMENTS, ToolMessages.ARGS_INVALID_JSON);
        return prepareTyped(tool.get(), call);
    }

    private <P> Prepare prepareTyped(Tool<P> tool, ContentBlock.ToolCall call) {
        Json arguments;
        try {
            arguments = tool.prepareArguments(call.arguments());
        } catch (RuntimeException e) {
            return immediate(ErrorKind.INVALID_ARGUMENTS, e.getMessage() != null ? e.getMessage() : describe(e));
        }
        P bound;
        try {
            bound = tool.params().bind(arguments);
        } catch (ArgumentException e) {
            return immediate(ErrorKind.INVALID_ARGUMENTS, e.render(call.name()));
        } catch (RuntimeException e) {
            return immediate(ErrorKind.INVALID_ARGUMENTS, describe(e));
        }
        ToolDecision decision;
        try {
            decision = deps.hooks().beforeToolCall(new BeforeToolCall(assistant, call, call.arguments(), arguments, ctx), deps.cancel());
            if (decision == null) throw new NullPointerException("beforeToolCall returned null");
        } catch (RuntimeException e) {
            return immediate(ErrorKind.HOOK_FAILED, describe(e));
        }
        switch (decision) {
            case ToolDecision.Block(var reason) -> { return immediate(ErrorKind.BLOCKED, reason.isBlank() ? ToolMessages.BLOCKED : reason); }
            case ToolDecision.Allow(var rewritten) -> {
                if (rewritten != null) {
                    arguments = rewritten;
                    try {
                        bound = tool.params().bind(rewritten);
                    } catch (ArgumentException | RuntimeException e) {
                        return immediate(ErrorKind.HOOK_FAILED, "beforeToolCall rewrote the arguments into something the tool cannot bind: " + describe(e));
                    }
                }
            }
        }
        return new Prepare.Prepared<>(tool, bound, arguments);
    }

    // ---- stage 2 -----------------------------------------------------------------------------

    private <P> ToolResult execute(Prepare.Prepared<P> p, ContentBlock.ToolCall call) {
        if (deps.cancel().isCancelled()) return CANCELLED;
        try {
            var invocation = new ToolInvocation<>(call.id(), call.name(), p.bound(), p.arguments(), deps.cancel(),
                    partial -> progress(call, partial));
            ToolResult r = p.tool().execute(invocation);
            return r == null ? new ToolResult.Ok(List.of(), Json.Null.NULL) : r;   // empty → "(no output)" at the exit
        } catch (Throwable t) {
            return failure(t, call);
        }
    }

    /// One gate per batch: a callback that raced settlement or cancellation cannot publish after `ToolEnd`.
    private synchronized void progress(ContentBlock.ToolCall call, ToolResult partial) {
        if (open && !deps.cancel().isCancelled()) {
            emit.accept(new AgentEvent.ToolUpdate(run.runId(), run.turnIndex(), now(), call.id(), call.name(), partial));
        }
    }

    private synchronized void seal() { open = false; }

    /// An interrupt of the driver thread is a request to stop the run: it cancels the token, so
    /// every later wait in this batch returns `CANCELLED` at once.
    private ToolResult await(Fork.Handle<ToolResult> handle) {
        try {
            return handle.get();
        } catch (ExecutionException e) {
            return ToolResult.error(ErrorKind.EXECUTION_FAILED, describe(e.getCause()));
        } catch (CancellationException _) {
            return CANCELLED;
        } catch (InterruptedException _) {
            deps.cancel().cancel();
            handle.cancel();
            Thread.currentThread().interrupt();
            return CANCELLED;
        }
    }

    private ToolResult failure(Throwable t, ContentBlock.ToolCall call) {
        return switch (t) {
            case InterruptedException _ -> { Thread.currentThread().interrupt(); yield CANCELLED; }
            case CancellationException _ -> CANCELLED;
            case ArgumentException e -> ToolResult.error(ErrorKind.INVALID_ARGUMENTS, e.render(call.name()));
            case ToolFailure f -> ToolResult.error(f.kind(), String.valueOf(t.getMessage()));
            default -> ToolResult.error(ErrorKind.EXECUTION_FAILED, describe(t));
        };
    }

    // ---- stage 3 -----------------------------------------------------------------------------

    private ToolResult applyAfterToolCall(ContentBlock.ToolCall call, Json arguments, ToolResult result) {
        try {
            return deps.hooks().afterToolCall(new AfterToolCall(assistant, call, arguments, result, result.isError(), ctx), deps.cancel())
                    .map(o -> o.applyTo(result)).orElse(result);
        } catch (RuntimeException e) {                       // fail closed: a broken filter must not leak the unfiltered result
            return ToolResult.error(ErrorKind.HOOK_FAILED, describe(e));
        }
    }

    // ---- stage 4: always reached -------------------------------------------------------------

    private ToolResultMessage emitOutcome(ContentBlock.ToolCall call, ToolResult result) {
        List<ContentBlock> content = result.content().isEmpty()
                ? List.of(ContentBlock.Text.of(ToolMessages.NO_OUTPUT))
                : result.content();
        boolean isError = result.isError();
        emit.accept(new AgentEvent.ToolEnd(run.runId(), run.turnIndex(), now(), call.id(), call.name(), result, isError));
        var message = new ToolResultMessage(call.id(), call.name(), content, result.details(), isError, now());
        emit.accept(new AgentEvent.MessageStart(run.runId(), run.turnIndex(), now(), message));
        emit.accept(new AgentEvent.MessageEnd(run.runId(), run.turnIndex(), now(), message));
        return message;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Prepare immediate(ErrorKind kind, String text) {
        return new Prepare.Immediate(ToolResult.error(kind, text));
    }

    private java.time.Instant now() { return deps.clock().instant(); }

    static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
    }
}
