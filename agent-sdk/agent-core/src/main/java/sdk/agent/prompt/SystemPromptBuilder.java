package sdk.agent.prompt;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/// Pure: sort by order (registration order breaks ties), filter, render, join, apply the override.
public final class SystemPromptBuilder {

    public static final String SEPARATOR = "\n\n";

    private SystemPromptBuilder() { }

    public static String build(PromptContext ctx, List<SectionSpec> sections, Optional<SystemPromptOverride> override) {
        if (override.orElse(null) instanceof SystemPromptOverride.Replace(String content)) return content;

        String body = sections.stream()
                .sorted(Comparator.comparingInt(SectionSpec::order))
                .filter(s -> s.when().test(ctx))
                .map(s -> s.render().apply(ctx))
                .filter(Predicate.not(String::isBlank))
                .collect(Collectors.joining(SEPARATOR));

        return override.orElse(null) instanceof SystemPromptOverride.Append(String content)
                ? body + SEPARATOR + content
                : body;
    }

    /// Section ids must be unique across contributors; the failure names both owners.
    public static void validate(Map<String, List<SectionSpec>> sectionsByContributor) {
        var owners = new HashMap<String, String>();
        for (var e : sectionsByContributor.entrySet()) {
            for (SectionSpec s : e.getValue()) {
                String previous = owners.putIfAbsent(s.id(), e.getKey());
                if (previous != null) {
                    throw new IllegalStateException("section id '%s' is contributed by both '%s' and '%s'"
                            .formatted(s.id(), previous, e.getKey()));
                }
            }
        }
    }
}
