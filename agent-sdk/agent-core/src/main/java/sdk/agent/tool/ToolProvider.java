package sdk.agent.tool;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/// A source of tools. Static packs use [#of]; dynamic sources (MCP `tools/list_changed`, skills)
/// publish a **new** catalog through [#catalogUpdates] — a registry is never mutated in place.
public interface ToolProvider {

    String id();

    ToolCatalog catalog();

    /// Completes immediately for a static provider.
    default Flow.Publisher<ToolCatalog> catalogUpdates() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            subscriber.onComplete();
        };
    }

    static ToolProvider of(String id, List<? extends Tool<?>> tools) {
        Objects.requireNonNull(id, "id");
        ToolCatalog catalog = ToolCatalog.of(tools);
        return new ToolProvider() {
            @Override public String id() { return id; }
            @Override public ToolCatalog catalog() { return catalog; }
        };
    }
}
