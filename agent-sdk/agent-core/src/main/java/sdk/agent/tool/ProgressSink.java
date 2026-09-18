package sdk.agent.tool;

/// Streaming partial results out of a running tool. [#update] delivers synchronously to the
/// listeners; an update that arrives after the call settled or the run was cancelled is dropped,
/// so an abandoned tool can never publish after its `ToolEnd`.
@FunctionalInterface
public interface ProgressSink {

    void update(ToolResult partial);

    ProgressSink NONE = _ -> { };
}
