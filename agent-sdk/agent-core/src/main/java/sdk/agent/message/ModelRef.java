package sdk.agent.message;

import java.util.Objects;

/// Which model spoke. Lives with the message model because every [AssistantMessage] records it;
/// the provider layer consumes it.
public record ModelRef(String api, String provider, String id, int contextWindow, int maxOutputTokens) {

    public static final ModelRef UNSET = new ModelRef("unknown", "unknown", "unknown", 0, 0);

    public ModelRef {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(id, "id");
    }
}
