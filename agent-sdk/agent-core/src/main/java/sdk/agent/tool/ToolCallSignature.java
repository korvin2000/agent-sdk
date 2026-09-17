package sdk.agent.tool;

import java.util.Objects;
import java.util.TreeMap;

import sdk.agent.json.Json;

/// Identity of a tool call for loop detection: canonical JSON (recursively key-sorted) so key
/// order cannot defeat it, a NUL separator so tool `a` with args `b:c` cannot collide with tool
/// `a:b` with args `c`, SHA-256 truncated to 12 hex.
public record ToolCallSignature(String value) {

    public ToolCallSignature { Objects.requireNonNull(value, "value"); }

    public static ToolCallSignature of(String toolName, Json arguments) {
        String canonical = canonical(arguments).toText();
        return new ToolCallSignature(Digests.sha256Hex(toolName + '\0' + canonical).substring(0, 12));
    }

    /// The same value with every object's members sorted by key, recursively.
    public static Json canonical(Json value) {
        return switch (value) {
            case Json.Obj o -> {
                var sorted = new TreeMap<String, Json>();
                o.members().forEach((k, v) -> sorted.put(k, canonical(v)));
                yield Json.obj(sorted);
            }
            case Json.Arr a -> Json.arr(a.values().stream().map(ToolCallSignature::canonical).toList());
            default -> value;
        };
    }
}
