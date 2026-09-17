package sdk.agent.prompt;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/// One system-prompt section: a unique id, an order, an inclusion predicate and a renderer.
/// A blank render drops the section, so "include when there is something to say" needs no predicate.
public record SectionSpec(String id, int order, Predicate<PromptContext> when, Function<PromptContext, String> render) {

    public SectionSpec {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("section id must not be blank");
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(render, "render");
    }

    /// A fixed text, always included.
    public static SectionSpec always(String id, int order, String text) {
        Objects.requireNonNull(text, "text");
        return new SectionSpec(id, order, _ -> true, _ -> text);
    }
}
