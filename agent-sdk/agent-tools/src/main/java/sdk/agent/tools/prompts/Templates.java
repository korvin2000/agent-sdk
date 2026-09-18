package sdk.agent.tools.prompts;

import sdk.agent.prompt.PromptTemplate;

/// This package's markdown templates. Loaded here, in the owning module, so JPMS allows the read.
final class Templates {

    private Templates() { }

    static PromptTemplate load(String name) {
        return PromptTemplate.read(Templates.class.getResourceAsStream(name), name);
    }
}
