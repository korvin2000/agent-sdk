package sdk.agent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A prompt text with `${name}` placeholders, usually a markdown resource. A placeholder without a
/// value fails loudly rather than reaching the model.
public record PromptTemplate(String text) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");

    public PromptTemplate { Objects.requireNonNull(text, "text"); }

    /// Reads and strips a UTF-8 resource. Call `getResourceAsStream` in the owning module: JPMS
    /// hides a module's resources from other modules.
    public static PromptTemplate read(InputStream resource, String name) {
        if (resource == null) throw new IllegalStateException("missing prompt template " + name);
        try (resource) {
            return new PromptTemplate(new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read prompt template " + name, e);
        }
    }

    public String render(Map<String, String> values) {
        return PLACEHOLDER.matcher(text).replaceAll(m -> {
            String value = values.get(m.group(1));
            if (value == null) throw new IllegalArgumentException("no value for ${" + m.group(1) + "}");
            return Matcher.quoteReplacement(value);
        });
    }
}
