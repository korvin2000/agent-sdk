package sdk.agent.prompt;

import java.util.Objects;

/// A host's last word on the system prompt: replace it wholesale, or append to the assembled one.
public sealed interface SystemPromptOverride {

    record Replace(String content) implements SystemPromptOverride {
        public Replace { Objects.requireNonNull(content, "content"); }
    }

    record Append(String content) implements SystemPromptOverride {
        public Append { Objects.requireNonNull(content, "content"); }
    }
}
