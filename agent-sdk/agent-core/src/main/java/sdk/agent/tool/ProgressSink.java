package sdk.agent.tool;

/// Partial tool results. The engine ignores updates after cancellation or batch settlement.
/// Listener callbacks run synchronously; pull-stream observation is bounded and lossy.
@FunctionalInterface
public interface ProgressSink {

    void update(ToolResult partial);

    ProgressSink NONE = _ -> { };
}
