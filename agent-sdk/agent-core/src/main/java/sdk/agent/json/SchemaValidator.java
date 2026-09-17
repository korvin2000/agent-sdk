package sdk.agent.json;

/// One-method SPI over core-owned types. The SDK ships [StructuralValidator]; a host that needs
/// full Draft 2020-12 supplies its own implementation as its own dependency, with no core change.
@FunctionalInterface
public interface SchemaValidator {

    /// Validates `arguments` against `schema` and returns the (possibly type-coerced) value to bind.
    /// @throws ArgumentException carrying every violation and the raw arguments
    Json validate(Json.Obj schema, Json arguments) throws ArgumentException;

    /// Structural validation with AJV-style coercion — the shipped default.
    SchemaValidator STRUCTURAL = new StructuralValidator();

    /// No validation at all: for schemas owned by a remote authority (MCP servers).
    SchemaValidator NONE = (_, arguments) -> arguments;
}
