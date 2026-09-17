package sdk.agent.provider;

import java.io.IOException;

/// A pull stream of [LlmStreamEvent]s. Deliberately **not** `AutoCloseable`: the pump owns it,
/// drains it from a forked thread and closes it from a cancellation callback — possibly while
/// [#next] is blocked in another thread — which try-with-resources cannot express.
public interface LlmStream {

    /// Returns `null` exactly once, at end of stream, and never again.
    LlmStreamEvent next() throws InterruptedException, IOException;

    /// Idempotent; safe to call concurrently with a blocked [#next]. Never throws checked.
    void close();
}
