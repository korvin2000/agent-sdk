package sdk.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/// An immutable snapshot of the tools a [ToolProvider] publishes. Order is prompt-visible.
public record ToolCatalog(SequencedMap<String, Tool<?>> tools) {

    public static final ToolCatalog EMPTY = new ToolCatalog(new LinkedHashMap<>());

    public ToolCatalog { tools = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(tools)); }

    /// @throws IllegalArgumentException if two tools in the list share a name
    public static ToolCatalog of(List<? extends Tool<?>> tools) {
        var byName = new LinkedHashMap<String, Tool<?>>();
        for (Tool<?> t : tools) {
            if (byName.putIfAbsent(t.name(), t) != null) {
                throw new IllegalArgumentException("duplicate tool name in catalog: " + t.name());
            }
        }
        return new ToolCatalog(byName);
    }
}
