package sdk.agent.tool;

import java.util.Objects;

import sdk.agent.json.ArgumentException;
import sdk.agent.json.Json;
import sdk.agent.json.JsonSchema;
import sdk.agent.json.ParamBinder;
import sdk.agent.json.StructuralValidator;

/// Schema **and** binder in one object. [#ofRecord] derives the schema once and binds through the
/// exact inverse; [#passthrough] hands opaque arguments to a tool that validates elsewhere (MCP).
///
/// @param <P> the bound parameter type
public interface ParamCodec<P> {

    Json.Obj schema();

    /// Validates (with coercion) and binds. The raw arguments survive inside the exception.
    P bind(Json arguments) throws ArgumentException;

    static <R extends Record> ParamCodec<R> ofRecord(Class<R> type) {
        Json.Obj schema = JsonSchema.ofRecord(type);
        return new ParamCodec<>() {
            @Override public Json.Obj schema() { return schema; }
            @Override public R bind(Json arguments) throws ArgumentException {
                return ParamBinder.bind(type, StructuralValidator.validate(schema, arguments));
            }
        };
    }

    /// No binding, no validation: the remote server is the authority.
    static ParamCodec<Json> passthrough(Json.Obj schema) {
        Objects.requireNonNull(schema, "schema");
        return new ParamCodec<>() {
            @Override public Json.Obj schema() { return schema; }
            @Override public Json bind(Json arguments) { return arguments; }
        };
    }
}
