package sdk.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;

import sdk.agent.json.Json;

/// Per-agent, immutable, **fail-closed**. Built once from every provider's catalog, in provider
/// order — which is the order the specs reach the provider and the prompt. [#resolve] is the only
/// lookup: unknown → empty, and the funnel turns that into `TOOL_NOT_FOUND`. There is no default
/// tool and no fallback to a global table.
public final class ToolRegistry {

    public static final ToolRegistry EMPTY = new ToolRegistry(Map.of());

    private final SequencedMap<String, ToolCatalog> catalogs;      // providerId -> catalog, provider order
    private final SequencedMap<String, Tool<?>> byName;            // insertion order == specs() order == prompt order
    private final Map<String, String> owner;                       // composed name -> provider id
    private final List<ToolSpec> specs;
    private final String hash;

    private ToolRegistry(Map<String, ToolCatalog> catalogs) {
        this.catalogs = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(catalogs));
        var names = new LinkedHashMap<String, Tool<?>>();
        var owners = new LinkedHashMap<String, String>();
        for (var e : this.catalogs.entrySet()) {
            for (Tool<?> tool : e.getValue().tools().values()) {
                String name = ToolNaming.requireValid(tool.name());
                String previous = owners.putIfAbsent(name, e.getKey());
                if (previous != null) throw new ToolNameCollisionException(name, previous, e.getKey());
                names.put(name, tool);
            }
        }
        this.byName = Collections.unmodifiableSequencedMap(names);
        this.owner = Map.copyOf(owners);
        this.specs = byName.values().stream().map(ToolSpec::of).toList();
        this.hash = Digests.sha256Hex(Json.arr(specs.stream().map(ToolSpec::toJson).toList()).toText());
    }

    /// @throws ToolNameCollisionException naming both owners
    public static ToolRegistry of(List<? extends ToolProvider> providers) {
        var catalogs = new LinkedHashMap<String, ToolCatalog>();
        for (ToolProvider p : providers) {
            if (catalogs.putIfAbsent(p.id(), p.catalog()) != null) {
                throw new IllegalArgumentException("duplicate tool provider id: " + p.id());
            }
        }
        return new ToolRegistry(catalogs);
    }

    public static ToolRegistry ofTools(String providerId, List<? extends Tool<?>> tools) {
        return of(List.of(ToolProvider.of(providerId, tools)));
    }

    public Optional<Tool<?>> resolve(String name) { return Optional.ofNullable(byName.get(name)); }

    public List<ToolSpec> specs()                  { return specs; }

    public List<Tool<?>> tools()                   { return List.copyOf(byName.values()); }

    public SequencedSet<String> names()            { return byName.sequencedKeySet(); }

    public Optional<String> ownerOf(String name)   { return Optional.ofNullable(owner.get(name)); }

    public int size()                              { return byName.size(); }

    /// SHA-256 over the specs, in order; what `RunState.toolSetHash` records.
    public String hash()                           { return hash; }

    /// Copy-on-write replacement of one provider's catalog; the receiver is untouched.
    public ToolRegistry with(String providerId, ToolCatalog replaced) {
        Objects.requireNonNull(providerId, "providerId");
        var next = new LinkedHashMap<>(catalogs);
        next.put(providerId, Objects.requireNonNull(replaced, "replaced"));
        return new ToolRegistry(next);
    }
}
