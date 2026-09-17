package sdk.agent.json;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// Derives a JSON Schema from a parameter record once, at registration, and caches it per class.
///
/// The mapping is small on purpose: `String` → string; integral primitives and boxes → integer;
/// floating types and `BigDecimal` → number; `boolean` → boolean; enums → string with `enum`
/// (lower-case constant names); `List<T>`/`Set<T>`/`T[]` → array; `Map<String,T>` → object with
/// `additionalProperties`; a nested record is **inlined** (never `$ref` — models handle flat schemas
/// better); `Optional<T>` is `T` omitted from `required`; a [Json] component accepts anything.
/// `"additionalProperties": false` is always emitted (it measurably cuts hallucinated parameters)
/// and properties keep declaration order, so the output is byte-stable for prompt caching.
public final class JsonSchema {

    private static final ClassValue<Json.Obj> CACHE = new ClassValue<>() {
        @Override protected Json.Obj computeValue(Class<?> type) { return objectSchema(type); }
    };

    private JsonSchema() { }

    public static Json.Obj ofRecord(Class<? extends Record> type) { return CACHE.get(type); }

    /// `true` for `Optional<T>`; [ParamBinder] relies on the same test.
    static boolean isOptional(Type t) {
        return t instanceof ParameterizedType p && p.getRawType() == Optional.class;
    }

    static Type typeArgument(Type t, int index) {
        if (t instanceof ParameterizedType p) {
            Type arg = p.getActualTypeArguments()[index];
            return arg instanceof WildcardType w && w.getUpperBounds().length > 0 ? w.getUpperBounds()[0] : arg;
        }
        return Object.class;
    }

    static Class<?> rawClass(Type t) {
        return switch (t) {
            case Class<?> c            -> c;
            case ParameterizedType p   -> (Class<?>) p.getRawType();
            case GenericArrayType g    -> rawClass(g.getGenericComponentType()).arrayType();
            case WildcardType w        -> rawClass(w.getUpperBounds()[0]);
            default                    -> Object.class;
        };
    }

    // ---- derivation ----------------------------------------------------------------------------

    private static Json.Obj objectSchema(Class<?> type) {
        if (!type.isRecord()) throw new IllegalArgumentException(type.getName() + " is not a record");
        var properties = new LinkedHashMap<String, Json>();
        var required = new ArrayList<Json>();
        for (RecordComponent c : type.getRecordComponents()) {
            properties.put(c.getName(), propertySchema(type, c));
            if (!isOptional(c.getGenericType())) required.add(Json.str(c.getName()));
        }
        var schema = new LinkedHashMap<String, Json>();
        schema.put("type", Json.str("object"));
        Doc doc = type.getAnnotation(Doc.class);
        if (doc != null) schema.put("description", Json.str(doc.value()));
        schema.put("properties", Json.obj(properties));
        if (!required.isEmpty()) schema.put("required", Json.arr(required));
        schema.put("additionalProperties", Json.Bool.FALSE);
        return Json.obj(schema);
    }

    private static Json.Obj propertySchema(Class<?> owner, RecordComponent c) {
        String where = owner.getSimpleName() + "." + c.getName();
        Type t = c.getGenericType();
        Type inner = isOptional(t) ? typeArgument(t, 0) : t;
        Json.Obj schema = schemaFor(inner, where);

        Doc doc = c.getAnnotation(Doc.class);
        if (doc != null) schema = schema.with("description", Json.str(doc.value()));
        Constraint constraint = c.getAnnotation(Constraint.class);
        if (constraint != null) schema = applyConstraint(schema, constraint, where);
        return schema;
    }

    private static Json.Obj applyConstraint(Json.Obj schema, Constraint constraint, String where) {
        String type = schema.get("type").map(t -> ((Json.Str) t).value()).orElse("");
        boolean numeric = type.equals("integer") || type.equals("number");
        if (constraint.min() != Long.MIN_VALUE) {
            requireType(numeric, where, "min", type);
            schema = schema.with("minimum", Json.num(constraint.min()));
        }
        if (constraint.max() != Long.MAX_VALUE) {
            requireType(numeric, where, "max", type);
            schema = schema.with("maximum", Json.num(constraint.max()));
        }
        if (constraint.minItems() > 0) {
            requireType(type.equals("array"), where, "minItems", type);
            schema = schema.with("minItems", Json.num(constraint.minItems()));
        }
        return schema;
    }

    private static void requireType(boolean ok, String where, String member, String actual) {
        if (!ok) throw new IllegalArgumentException("@Constraint(" + member + ") on " + where + " needs a compatible type, found \"" + actual + "\"");
    }

    private static Json.Obj schemaFor(Type t, String where) {
        Class<?> raw = rawClass(t);
        if (raw == String.class || raw == CharSequence.class)                              return typed("string");
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class
                || raw == BigInteger.class)                                                 return typed("integer");
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                || raw == BigDecimal.class)                                                 return typed("number");
        if (raw == boolean.class || raw == Boolean.class)                                   return typed("boolean");
        if (raw.isEnum())                                                                   return enumSchema(raw);
        if (raw == Json.class)                                                              return Json.Obj.EMPTY;
        if (raw == Json.Obj.class)                                                          return typed("object");
        if (raw == Json.Arr.class)                                                          return typed("array");
        if (raw == Json.Str.class)                                                          return typed("string");
        if (raw == Json.Num.class)                                                          return typed("number");
        if (raw == Json.Bool.class)                                                         return typed("boolean");
        if (raw.isArray())                                                                  return typed("array").with("items", schemaFor(componentType(t), where));
        if (Collection.class.isAssignableFrom(raw))                                         return typed("array").with("items", schemaFor(typeArgument(t, 0), where));
        if (Map.class.isAssignableFrom(raw)) {
            if (rawClass(typeArgument(t, 0)) != String.class) throw new IllegalArgumentException(where + ": only Map<String, T> is supported");
            return typed("object").with("additionalProperties", schemaFor(typeArgument(t, 1), where));
        }
        if (raw.isRecord())                                                                 return CACHE.get(raw);
        if (isOptional(t)) throw new IllegalArgumentException(where + ": Optional is only supported at the top level of a component");
        throw new IllegalArgumentException(where + ": unsupported parameter type " + t.getTypeName());
    }

    private static Type componentType(Type t) {
        return t instanceof GenericArrayType g ? g.getGenericComponentType() : ((Class<?>) t).getComponentType();
    }

    private static Json.Obj enumSchema(Class<?> raw) {
        var names = new ArrayList<Json>();
        for (Object constant : raw.getEnumConstants()) names.add(Json.str(enumName((Enum<?>) constant)));
        return typed("string").with("enum", Json.arr(names));
    }

    /// The model-facing spelling of an enum constant: `IN_PROGRESS` → `in_progress`.
    static String enumName(Enum<?> constant) { return constant.name().toLowerCase(Locale.ROOT); }

    private static Json.Obj typed(String type) { return Json.obj("type", Json.str(type)); }
}
