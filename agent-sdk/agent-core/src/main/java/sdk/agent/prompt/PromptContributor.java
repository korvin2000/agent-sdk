package sdk.agent.prompt;

import java.util.List;

/// A source of sections. Tool packs, skills and hosts contribute through this; the core's builder
/// only sorts, filters, renders and joins.
@FunctionalInterface
public interface PromptContributor {
    List<SectionSpec> sections();
}
