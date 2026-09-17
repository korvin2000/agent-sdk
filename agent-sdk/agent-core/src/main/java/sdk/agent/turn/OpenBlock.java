package sdk.agent.turn;

import java.util.Objects;

import sdk.agent.message.ContentBlock;

/// The one content block currently open on the stream; at most one at a time. Appends are pure. A
/// delta is opaque and is never inspected before [#close]. `text + delta` is O(n) per delta, which
/// for a response measured in tens of kilobytes is unmeasurable next to the network; a
/// `StringBuilder` inside a record that must serialise at every checkpoint is not a trade worth making.
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
