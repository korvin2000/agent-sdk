package sdk.agent.turn;

import java.util.Objects;

import sdk.agent.message.ContentBlock;

/// An immutable, indexed text or thinking accumulator. Appending copies the current text;
/// this keeps transitions pure but is an explicit per-delta allocation cost.
public record OpenBlock(Kind kind, int index, String text, String signature, boolean redacted) {

    public enum Kind { TEXT, THINKING }

    public OpenBlock {
        Objects.requireNonNull(kind, "kind");
        text = Objects.requireNonNullElse(text, "");
    }

    public static OpenBlock text(int index)     { return new OpenBlock(Kind.TEXT, index, "", null, false); }
    public static OpenBlock thinking(int index) { return new OpenBlock(Kind.THINKING, index, "", null, false); }

    public OpenBlock append(String delta) { return new OpenBlock(kind, index, text + delta, signature, redacted); }

    /// The final text from the `*End` event wins when present; otherwise the accumulated deltas.
    public OpenBlock finish(String finalText, String finalSignature, boolean finalRedacted) {
        return new OpenBlock(kind, index,
                finalText != null ? finalText : text,
                finalSignature != null ? finalSignature : signature,
                redacted || finalRedacted);
    }

    public ContentBlock close() {
        return kind == Kind.TEXT
                ? new ContentBlock.Text(text, signature)
                : new ContentBlock.Thinking(text, signature, redacted);
    }
}
