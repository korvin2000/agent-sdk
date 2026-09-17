package sdk.agent.tool;

/// Streaming partial results out of a running tool. [#update] **blocks** — backpressure for free on
/// a virtual thread, and no batching array, no flush-after-resolve and no late-emit crash.
@FunctionalInterface
public interface ProgressSink {

    void update(ToolResult partial);

    ProgressSink NONE = _ -> { };
}
