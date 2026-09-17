package sdk.agent.json;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import sdk.agent.json.ArgumentException.Violation;

/// The exact inverse of [JsonSchema]: reflection over `getRecordComponents()` plus the canonical
/// constructor. Record component names are in the class file, so no `-parameters` flag is needed.
/// Every mismatch becomes a [Violation] at an AJV-style path rather than a reflection exception,
/// so a binding failure is a reprompt the model can act on. Parameter records must be public in an
/// exported (or open) package.
public final class ParamBinder {

    private ParamBinder() { }

    public static <R extends Record> R bind(Class<R> type, Json value) throws ArgumentException {
        var errors = new ArrayList<Violation>();
        Object bound = convert(type, value, Violation.ROOT, errors);
        if (!errors.isEmpty()) throw new ArgumentException(errors, value);
        return type.cast(bound);
    }

    /// `value == null` means "absent". Returns `null` when a violation was recorded.
    private static Object convert(Type target, Json value, String path, List<Violation> errors) {
        if (JsonSchema.isOptional(target)) {
            if (value == null || value == Json.Null.NULL) return Optional.empty();
            Object inner = convert(JsonSchema.typeArgument(target, 0), value, path, errors);
            return inner == null ? Optional.empty() : Optional.of(inner);
        }
        Class<?> raw = JsonSchema.rawClass(target);
        if (value == null || value == Json.Null.NULL) {
            if (value == null) return null;                     // the enclosing record reports the missing property
            errors.add(new Violation(path, "must be " + typeWord(raw)));
            return null;
        }
        if (Json.class.isAssignableFrom(raw)) {
            if (raw.isInstance(value)) return value;
            errors.add(new Violation(path, "must be " + typeWord(raw)));
            return null;
        }
        if (raw == String.class || raw == CharSequence.class) return expect(Json.Str.class, value, "string", path, errors, Json.Str::value);
        if (raw == boolean.class || raw == Boolean.class)     return expect(Json.Bool.class, value, "boolean", path, errors, Json.Bool::value);
        if (raw == int.class || raw == Integer.class)         return integral(value, path, errors, n -> n.value().intValueExact());
        if (raw == long.class || raw == Long.class)           return integral(value, path, errors, n -> n.value().longValueExact());
        if (raw == short.class || raw == Short.class)         return integral(value, path, errors, n -> n.value().shortValueExact());
        if (raw == byte.class || raw == Byte.class)           return integral(value, path, errors, n -> n.value().byteValueExact());
        if (raw == BigInteger.class)                          return integral(value, path, errors, n -> n.value().toBigIntegerExact());
        if (raw == double.class || raw == Double.class)       return expect(Json.Num.class, value, "number", path, errors, Json.Num::asDouble);
        if (raw == float.class || raw == Float.class)         return expect(Json.Num.class, value, "number", path, errors, n -> n.value().floatValue());
        if (raw == BigDecimal.class)                          return expect(Json.Num.class, value, "number", path, errors, Json.Num::value);
        if (raw.isEnum())                                     return enumConstant(raw, value, path, errors);
        if (raw.isArray())                                    return array(raw.getComponentType(), target, value, path, errors);
        if (Collection.class.isAssignableFrom(raw))           return collection(raw, JsonSchema.typeArgument(target, 0), value, path, errors);
        if (Map.class.isAssignableFrom(raw))                  return map(JsonSchema.typeArgument(target, 1), value, path, errors);
        if (raw.isRecord())                                   return record(raw, value, path, errors);
        throw new IllegalArgumentException("unsupported parameter type " + target.getTypeName() + " at " + (path.isEmpty() ? "root" : path));
    }

    private interface Extract<J extends Json, T> { T apply(J json); }

    private static <J extends Json, T> T expect(Class<J> kind, Json value, String word, String path, List<Violation> errors, Extract<J, T> extract) {
        if (kind.isInstance(value)) return extract.apply(kind.cast(value));
        errors.add(new Violation(path, "must be " + word));
        return null;
    }

    private static <T> T integral(Json value, String path, List<Violation> errors, Extract<Json.Num, T> narrow) {
        if (value instanceof Json.Num n && n.isIntegral()) {
            try { return narrow.apply(n); }
            catch (ArithmeticException _) { errors.add(new Violation(path, "must be an integer in range")); return null; }
        }
        errors.add(new Violation(path, "must be integer"));
        return null;
    }

    private static Object enumConstant(Class<?> raw, Json value, String path, List<Violation> errors) {
        if (value instanceof Json.Str s) {
            for (Object constant : raw.getEnumConstants()) {
                Enum<?> e = (Enum<?>) constant;
                if (JsonSchema.enumName(e).equals(s.value()) || e.name().equalsIgnoreCase(s.value())) return e;
            }
        }
        errors.add(new Violation(path, "must be equal to one of the allowed values"));
        return null;
    }

    private static Object array(Class<?> component, Type target, Json value, String path, List<Violation> errors) {
        if (!(value instanceof Json.Arr arr)) { errors.add(new Violation(path, "must be array")); return null; }
        Type elementType = target instanceof java.lang.reflect.GenericArrayType g ? g.getGenericComponentType() : component;
        Object out = Array.newInstance(component, arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = convert(elementType, arr.get(i), join(path, i), errors);
            if (element != null) Array.set(out, i, element);
        }
        return out;
    }

    private static Object collection(Class<?> raw, Type elementType, Json value, String path, List<Violation> errors) {
        if (!(value instanceof Json.Arr arr)) { errors.add(new Violation(path, "must be array")); return null; }
        var out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) out.add(convert(elementType, arr.get(i), join(path, i), errors));
        if (out.contains(null)) return null;
        if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>(out);
        return List.copyOf(out);
    }

    private static Object map(Type valueType, Json value, String path, List<Violation> errors) {
        if (!(value instanceof Json.Obj obj)) { errors.add(new Violation(path, "must be object")); return null; }
        var out = new LinkedHashMap<String, Object>();
        for (var e : obj.members().entrySet()) {
            Object v = convert(valueType, e.getValue(), join(path, e.getKey()), errors);
            if (v != null) out.put(e.getKey(), v);
        }
        return Map.copyOf(out);
    }

    private static Object record(Class<?> raw, Json value, String path, List<Violation> errors) {
        if (!(value instanceof Json.Obj obj)) { errors.add(new Violation(path, "must be object")); return null; }
        RecordComponent[] components = raw.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        int before = errors.size();
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            types[i] = c.getType();
            Json member = obj.members().get(c.getName());
            if (member == null && !JsonSchema.isOptional(c.getGenericType())) {
                errors.add(new Violation(path.isEmpty() ? c.getName() : path, "must have required property '" + c.getName() + "'"));
                continue;
            }
            args[i] = convert(c.getGenericType(), member, join(path, c.getName()), errors);   // null only with a violation recorded
        }
        if (errors.size() > before) return null;
        try {
            Constructor<?> ctor = raw.getDeclaredConstructor(types);
            try { ctor.setAccessible(true); } catch (RuntimeException _) { /* exported package: public ctor works as is */ }
            return ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            errors.add(new Violation(path, String.valueOf(e.getCause().getMessage())));   // a compact-constructor check
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate parameter record " + raw.getName(), e);
        }
    }

    private static String typeWord(Class<?> raw) {
        if (raw == Json.Obj.class || raw.isRecord() || Map.class.isAssignableFrom(raw)) return "object";
        if (raw == Json.Arr.class || raw.isArray() || Collection.class.isAssignableFrom(raw)) return "array";
        if (raw == Json.Num.class || raw == double.class || raw == Double.class || raw == float.class || raw == Float.class || raw == BigDecimal.class) return "number";
        if (raw == Json.Bool.class || raw == boolean.class || raw == Boolean.class) return "boolean";
        if (raw == Json.Str.class || raw == String.class || raw.isEnum()) return "string";
        if (raw == Json.class) return "a JSON value";
        return "integer";
    }

    private static String join(String path, String segment) { return path.isEmpty() ? segment : path + "/" + segment; }
    private static String join(String path, int index)      { return join(path, Integer.toString(index)); }
}
