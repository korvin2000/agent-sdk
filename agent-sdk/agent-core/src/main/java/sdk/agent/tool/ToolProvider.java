package sdk.agent.tool;

import java.util.List;
import java.util.Objects;

/// A source of tools. Static packs use [#of]; a dynamic source (MCP `tools/list_changed`) returns
/// a **new** list from [#tools] and the registry is rebuilt from it when the next run starts —
/// a registry is never mutated in place.
public interface ToolProvider {

    String id();

    List<Tool<?>> tools();

    static ToolProvider of(String id, List<? extends Tool<?>> tools) {
        Objects.requireNonNull(id, "id");
        List<Tool<?>> copy = List.copyOf(tools);
        return new ToolProvider() {
            @Override public String id() { return id; }
            @Override public List<Tool<?>> tools() { return copy; }
        };
    }
}
