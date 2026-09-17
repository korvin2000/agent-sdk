package sdk.agent.tool;

/// An exception that knows which [ErrorKind] it is. The funnel maps a thrown `ToolFailure` to
/// `Err(kind(), getMessage())` — so a tool pack's own exception types (a refused path, an edit
/// that did not match) become model-facing results without the funnel knowing the types.
public interface ToolFailure {
    ErrorKind kind();
}
