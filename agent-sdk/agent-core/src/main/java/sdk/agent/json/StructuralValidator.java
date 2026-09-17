package sdk.agent.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import sdk.agent.json.ArgumentException.Violation;

/// Structural JSON Schema validation: declared `type` per property, `required`, `enum`,
/// `additionalProperties: false`, and the three bounds `minimum` / `maximum` / `minItems`.
/// Deliberately **not** implemented: `$ref`, `oneOf`/`anyOf`/`allOf`, `format`, `pattern` — a
/// false rejection costs the model a whole turn, so precision beats completeness.
///
/// Coercion runs first, on a copy, and is load-bearing rather than lenient: models emit `"5"` for
/// an integer often enough that AJV's `coerceTypes` is what keeps pi-mono alive. The raw arguments
/// survive untouched for the error message. Every violation is collected (not just the first) so
/// the model can fix them all in one reprompt; messages use AJV's wording, which is what the
/// upstream prompts were tuned against.
public final class StructuralValidator implements SchemaValidator {

    @Override public Json validate(Json.Obj schema, Json arguments) throws ArgumentException {
        Json coerced = coerce(schema, arguments);
        var errors = new ArrayList<Violation>();
        check(schema, coerced, Violation.ROOT, errors);
        if (!errors.isEmpty()) throw new ArgumentException(errors, arguments);
        return coerced;
    }

    // ---- coercion ------------------------------------------------------------------------------

    private static Json coerce(Json.Obj schema, Json value) {
        return switch (typeOf(schema).orElse("")) {
            case "string"  -> switch (value) {
                case Json.Num n  -> Json.str(n.value().toPlainString());
                case Json.Bool b -> Json.str(Boolean.toString(b.value()));
                default          -> value;
            };
            case "integer", "number" -> value instanceof Json.Str s ? parseNumber(s.value()).map(Json::num).map(Json.class::cast).orElse(value) : value;
            case "boolean" -> switch (value) {
                case Json.Str s when s.value().equals("true")  -> Json.Bool.TRUE;
                case Json.Str s when s.value().equals("false") -> Json.Bool.FALSE;
                default -> value;
            };
            case "array"   -> {
                Json.Arr arr = switch (value) {
                    case Json.Arr a -> a;
                    case Json.Str _, Json.Num _, Json.Bool _ -> Json.arr(value);   // scalar -> [scalar]
                    default -> null;
                };
                if (arr == null) yield value;
                Optional<Json.Obj> items = objectMember(schema, "items");
                if (items.isEmpty()) yield arr;
                yield Json.arr(arr.values().stream().map(v -> coerce(items.get(), v)).toList());
            }
            case "object"  -> value instanceof Json.Obj o ? coerceObject(schema, o) : value;
            default        -> value;
        };
    }

    private static Json coerceObject(Json.Obj schema, Json.Obj value) {
        Optional<Json.Obj> properties = objectMember(schema, "properties");
        Optional<Json.Obj> additional = objectMember(schema, "additionalProperties");
        var out = new LinkedHashMap<String, Json>();
        for (var e : value.members().entrySet()) {
            Optional<Json.Obj> propertySchema = properties.flatMap(p -> objectMember(p, e.getKey()));
            Json.Obj target = propertySchema.or(() -> additional).orElse(null);
            out.put(e.getKey(), target == null ? e.getValue() : coerce(target, e.getValue()));
        }
        return Json.obj(out);
    }

    private static Optional<BigDecimal> parseNumber(String text) {
        try { return Optional.of(new BigDecimal(text.strip())); }
        catch (NumberFormatException _) { return Optional.empty(); }
    }

    // ---- checks --------------------------------------------------------------------------------

    private static void check(Json.Obj schema, Json value, String path, List<Violation> errors) {
        Optional<String> type = typeOf(schema);
        if (type.isPresent() && !matchesType(type.get(), value)) {
            errors.add(new Violation(path, "must be " + type.get()));
            return;                                             // nothing below applies to the wrong kind
        }
        schema.get("enum").filter(Json.Arr.class::isInstance).map(Json.Arr.class::cast).ifPresent(allowed -> {
            if (!allowed.values().contains(value)) errors.add(new Violation(path, "must be equal to one of the allowed values"));
        });
        switch (value) {
            case Json.Obj o -> checkObject(schema, o, path, errors);
            case Json.Arr a -> checkArray(schema, a, path, errors);
            case Json.Num n -> checkNumber(schema, n, path, errors);
            default -> { }
        }
    }

    private static void checkObject(Json.Obj schema, Json.Obj value, String path, List<Violation> errors) {
        Optional<Json.Obj> properties = objectMember(schema, "properties");
        schema.get("required").filter(Json.Arr.class::isInstance).map(Json.Arr.class::cast).ifPresent(required -> {
            for (Json r : required.values()) {
                if (r instanceof Json.Str name && !value.has(name.value())) {
                    errors.add(new Violation(path.isEmpty() ? name.value() : path, "must have required property '" + name.value() + "'"));
                }
            }
        });
        Json additional = schema.get("additionalProperties").orElse(null);
        for (var e : value.members().entrySet()) {
            Optional<Json.Obj> propertySchema = properties.flatMap(p -> objectMember(p, e.getKey()));
            if (propertySchema.isPresent()) {
                check(propertySchema.get(), e.getValue(), join(path, e.getKey()), errors);
            } else if (additional instanceof Json.Bool b && !b.value()) {
                errors.add(new Violation(path, "must NOT have additional properties"));
            } else if (additional instanceof Json.Obj extra) {
                check(extra, e.getValue(), join(path, e.getKey()), errors);
            }
        }
    }

    private static void checkArray(Json.Obj schema, Json.Arr value, String path, List<Violation> errors) {
        schema.get("minItems").filter(Json.Num.class::isInstance).map(Json.Num.class::cast).ifPresent(min -> {
            if (value.size() < min.asLong()) errors.add(new Violation(path, "must NOT have fewer than " + min.asLong() + " items"));
        });
        objectMember(schema, "items").ifPresent(items -> {
            for (int i = 0; i < value.size(); i++) check(items, value.get(i), join(path, Integer.toString(i)), errors);
        });
    }

    private static void checkNumber(Json.Obj schema, Json.Num value, String path, List<Violation> errors) {
        schema.get("minimum").filter(Json.Num.class::isInstance).map(Json.Num.class::cast).ifPresent(min -> {
            if (value.value().compareTo(min.value()) < 0) errors.add(new Violation(path, "must be >= " + min.value().toPlainString()));
        });
        schema.get("maximum").filter(Json.Num.class::isInstance).map(Json.Num.class::cast).ifPresent(max -> {
            if (value.value().compareTo(max.value()) > 0) errors.add(new Violation(path, "must be <= " + max.value().toPlainString()));
        });
    }

    private static boolean matchesType(String type, Json value) {
        return switch (type) {
            case "object"  -> value instanceof Json.Obj;
            case "array"   -> value instanceof Json.Arr;
            case "string"  -> value instanceof Json.Str;
            case "number"  -> value instanceof Json.Num;
            case "integer" -> value instanceof Json.Num n && n.isIntegral();
            case "boolean" -> value instanceof Json.Bool;
            case "null"    -> value instanceof Json.Null;
            default        -> true;                            // unknown type word: do not reject
        };
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Optional<String> typeOf(Json.Obj schema) {
        return schema.get("type").filter(Json.Str.class::isInstance).map(t -> ((Json.Str) t).value());
    }

    private static Optional<Json.Obj> objectMember(Json.Obj obj, String key) {
        return obj.get(key).filter(Json.Obj.class::isInstance).map(Json.Obj.class::cast);
    }

    private static String join(String path, String segment) {
        return path.isEmpty() ? segment : path + "/" + segment;
    }
}
