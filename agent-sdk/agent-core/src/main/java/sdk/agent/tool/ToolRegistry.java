package sdk.agent.tool;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;

import sdk.agent.json.Json;

/// Per-run, immutable, **fail-closed**. Built from every provider's tools, in provider order — which
/// is the order the specs reach the provider and the prompt. [#resolve] is the only lookup: unknown
/// → empty, and the funnel turns that into `TOOL_NOT_FOUND`. There is no default tool and no
/// fallback to a global table.
public final class ToolRegistry {

    public static final ToolRegistry EMPTY = new ToolRegistry(List.of());

    private final SequencedMap<String, Tool<?>> byName;            // insertion order == specs() order == prompt order
    private final List<ToolSpec> specs;
    private final String hash;

    private ToolRegistry(List<? extends ToolProvider> providers) {
        var names = new LinkedHashMap<String, Tool<?>>();
        var owners = new LinkedHashMap<String, String>();
        var ids = new HashSet<String>();
        for (ToolProvider p : providers) {
            if (!ids.add(p.id())) throw new IllegalArgumentException("duplicate tool provider id: " + p.id());
            for (Tool<?> tool : p.tools()) {
                String name = ToolNaming.requireValid(tool.name());
                String previous = owners.putIfAbsent(name, p.id());
                if (previous != null) throw new ToolNameCollisionException(name, previous, p.id());
                names.put(name, tool);
            }
        }
        this.byName = Collections.unmodifiableSequencedMap(names);
        this.specs = byName.values().stream().map(ToolSpec::of).toList();
        this.hash = Digests.sha256Hex(Json.arr(specs.stream().map(ToolSpec::toJson).toList()).toText());
    }

    /// @throws ToolNameCollisionException naming both owners
    public static ToolRegistry of(List<? extends ToolProvider> providers) { return new ToolRegistry(providers); }

    public static ToolRegistry ofTools(String providerId, List<? extends Tool<?>> tools) {
        return of(List.of(ToolProvider.of(providerId, tools)));
    }

    public Optional<Tool<?>> resolve(String name) { return Optional.ofNullable(byName.get(name)); }

    public List<ToolSpec> specs()                  { return specs; }

    public List<Tool<?>> tools()                   { return List.copyOf(byName.values()); }

    public SequencedSet<String> names()            { return byName.sequencedKeySet(); }

    public int size()                              { return byName.size(); }

    /// SHA-256 over the specs, in order; what `RunState.toolSetHash` records.
    public String hash()                           { return hash; }
}
