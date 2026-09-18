package sdk.agent.tools.support;

import java.util.Objects;

import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolFailure;

/// A refusal on the way to the model: `getMessage()` **is** the text the model reads, because the
/// funnel converts any thrown [ToolFailure] to `Err(kind(), getMessage())` at one place. Unchecked
/// and without a stack trace — this is a message, not a crash — so tool bodies read as straight-line
/// code with no `throws` clause per refusal.
public final class ToolException extends RuntimeException implements ToolFailure {

    private final ErrorKind kind;

    public ToolException(String message) { this(ErrorKind.EXECUTION_FAILED, message); }

    public ToolException(ErrorKind kind, String message) {
        super(message, null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /// The model sent arguments this tool cannot act on.
    public static ToolException invalid(String message) { return new ToolException(ErrorKind.INVALID_ARGUMENTS, message); }

    @Override public ErrorKind kind() { return kind; }
}
