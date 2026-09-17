package sdk.agent.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// A user turn. `content` is **always a list** — there is no string form.
public record UserMessage(List<ContentBlock> content, Instant timestamp) implements Message {

    public UserMessage {
        content = List.copyOf(content);
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public static UserMessage text(String text) { return text(text, Instant.now()); }

    public static UserMessage text(String text, Instant at) {
        return new UserMessage(List.of(new ContentBlock.Text(text, null)), at);
    }

    public static UserMessage of(String text, List<ContentBlock.Image> images) {
        var blocks = new ArrayList<ContentBlock>(images.size() + 1);
        blocks.add(new ContentBlock.Text(text, null));
        blocks.addAll(images);
        return new UserMessage(blocks, Instant.now());
    }

    /// The concatenated text blocks; images and other blocks are skipped.
    public String text() { return ContentBlock.textOf(content); }

    @Override public String kind() { return "user"; }
}
