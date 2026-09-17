package sdk.agent.message;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import sdk.agent.json.Json;

/// The six content kinds. `Audio` and `Resource` exist from day one so an MCP content mapper can be
/// total; a `resource_link` is a [Resource] with neither text nor blob. Binary payloads are
/// **base64 strings, not `byte[]`**: a record with an array component gets identity equality and a
/// mutable accessor, and both ends of the wire already speak base64.
///
/// Provider round-trip tokens (`signature`, `thoughtSignature`, `redacted`) are opaque and must
/// survive verbatim or multi-turn reasoning breaks.
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.Thinking, ContentBlock.Image,
                ContentBlock.Audio, ContentBlock.Resource, ContentBlock.ToolCall {

    record Text(String text, String signature) implements ContentBlock {
        public Text { Objects.requireNonNull(text, "text"); }
        public static Text of(String text) { return new Text(text, null); }
    }

    record Thinking(String thinking, String signature, boolean redacted) implements ContentBlock {
        public Thinking { Objects.requireNonNull(thinking, "thinking"); }
    }

    /// `data` is base64.
    record Image(String data, String mimeType) implements ContentBlock {
        public Image { Objects.requireNonNull(data, "data"); Objects.requireNonNull(mimeType, "mimeType"); }
    }

    /// `data` is base64.
    record Audio(String data, String mimeType) implements ContentBlock {
        public Audio { Objects.requireNonNull(data, "data"); Objects.requireNonNull(mimeType, "mimeType"); }
    }

    /// `blob` is base64. Both empty means a reference with no inline content.
    record Resource(URI uri, String mimeType, Optional<String> text, Optional<String> blob) implements ContentBlock {
        public Resource {
            Objects.requireNonNull(uri, "uri");
            text = text == null ? Optional.empty() : text;
            blob = blob == null ? Optional.empty() : blob;
        }
    }

    record ToolCall(String id, String name, Json arguments, String thoughtSignature) implements ContentBlock {
        public ToolCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            arguments = arguments == null ? Json.Obj.EMPTY : arguments;
        }
    }

    /// The text blocks of a content list, concatenated.
    static String textOf(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(Text.class::isInstance)
                .map(b -> ((Text) b).text())
                .collect(Collectors.joining());
    }
}
