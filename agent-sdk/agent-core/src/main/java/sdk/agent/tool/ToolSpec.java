package sdk.agent.tool;

import java.util.Objects;

import sdk.agent.json.Json;

/// The provider-facing projection of a tool: exactly what the wire format has, and no prompt
/// fields — anything added here ships to the model as part of the tool definition.
public record ToolSpec(String name, String description, Json.Obj inputSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
    }

    public static ToolSpec of(Tool<?> tool) {
        return new ToolSpec(tool.name(), tool.description(), tool.params().schema());
    }

    public Json.Obj toJson() {
        return Json.obj("name", Json.str(name), "description", Json.str(description), "inputSchema", inputSchema);
    }
}
