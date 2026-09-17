package sdk.agent.provider;

import java.util.Iterator;
import java.util.List;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.message.StopReason;
import sdk.agent.message.Usage;

/// Makes a freshly built agent runnable: every request answers `Done(STOP)` with no content.
public final class StubProvider implements LlmProvider {

    @Override public LlmStream stream(LlmRequest request, Cancellation cancel) {
        Iterator<LlmStreamEvent> it = List.<LlmStreamEvent>of(
                new LlmStreamEvent.Start(),
                new LlmStreamEvent.Done(StopReason.STOP, Usage.EMPTY, "stub-1")).iterator();
        return new LlmStream() {
            @Override public LlmStreamEvent next() { return it.hasNext() ? it.next() : null; }
            @Override public void close() { }
        };
    }
}
