package sdk.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.QueueSink;

/// The handle `Agent.prompt()`/`resume()` return. One per run; the event side and the result side
/// are separate objects with separate completion rules, so neither can starve the other.
public final class AgentRun {

    private final String runId;
    private final Cancellation cancel;
    private final QueueSink events;
    private final CompletableFuture<RunResult> result = new CompletableFuture<>();
    private volatile RunState state;

    AgentRun(String runId, Cancellation cancel, QueueSink events, RunState initial) {
        this.runId = Objects.requireNonNull(runId);
        this.cancel = Objects.requireNonNull(cancel);
        this.events = Objects.requireNonNull(events);
        this.state = Objects.requireNonNull(initial);
    }

    public String runId() { return runId; }

    /// Completes on `RunEnd` on **every** path — `Completed`, `Aborted`, `Failed`, `LimitExceeded` —
    /// and never exceptionally: a failure is a [sdk.agent.event.RunOutcome], not a thrown exception.
    public CompletableFuture<RunResult> result() { return result; }

    /// This run's slice of the event stream: single-consumer, blocking, ends after `RunEnd`. It is
    /// lossy by design — the newest 256 events are kept and a consumer that stops reading never
    /// stalls the run; [#result] and the transcript are authoritative.
    public Stream<AgentEvent> events() { return events.stream(); }

    /// The last durable checkpoint.
    public RunState state() { return state; }

    public boolean isDone() { return result.isDone(); }

    public void abort() { cancel.cancel(); }

    void checkpoint(RunState s) { state = s; }

    void complete(RunResult r) { state = r.state(); result.complete(r); }
}
