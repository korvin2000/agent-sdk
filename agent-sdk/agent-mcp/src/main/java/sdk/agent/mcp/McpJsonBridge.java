package sdk.agent.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import sdk.agent.json.Json;

/// Core [Json] ⇄ the MCP SDK's untyped representation. The SDK models arguments, `inputSchema`,
/// `structuredContent` and `_meta` as `Map<String, Object>` / `List<Object>` / boxed primitives:
/// `mcp-json-jackson2` binds them with a stock Jackson `ObjectMapper`, so an integer arrives as
/// `Integer`/`Long`/`BigInteger` and a decimal as `Double`.
///
/// Numbers survive exactly in both directions. [Json.Num] holds a `BigDecimal`, so a 64-bit id is
/// not rounded through a `double`; going out, an integral value is emitted as `Long`/`BigInteger`
/// rather than a `BigDecimal` with a negative scale, which Jackson would write as `1E+3`.
///
/// Member order is preserved: [Json.Obj] is insertion-ordered and this uses `LinkedHashMap`, which
/// is what keeps a passthrough `inputSchema` byte-stable for prompt caching.
final class McpJsonBridge {

    private McpJsonBridge() { }

    /// The `arguments` shape `CallToolRequest` wants. A non-object (the model sent a bare array or
    /// string) becomes an empty map rather than an exception — the remote server is the authority
    /// on its own schema and reports the mismatch itself.
    static Map<String, Object> toMap(Json json) {
        var out = new LinkedHashMap<String, Object>();
        if (json instanceof Json.Obj obj) {
            obj.members().forEach((k, v) -> out.put(k, toSdk(v)));
        }
        return out;
    }

    static Object toSdk(Json json) {
        return switch (json) {
            case Json.Null _ -> null;
            case Json.Bool b -> b.value();
            case Json.Str s -> s.value();
            case Json.Num n -> toSdkNumber(n.value());
            case Json.Arr a -> {
                var list = new ArrayList<>(a.size());
                for (Json v : a.values()) list.add(toSdk(v));
                yield list;
            }
            case Json.Obj o -> toMap(o);
        };
    }

    private static Number toSdkNumber(BigDecimal d) {
        if (d.scale() > 0) return d;
        try {
            return d.longValueExact();
        } catch (ArithmeticException _) {
            return d.toBigIntegerExact();
        }
    }

    /// The inverse. Anything the SDK could hand back is covered; an unrecognised object degrades
    /// **visibly**, to its string form, rather than being dropped.
    static Json toJson(Object value) {
        return switch (value) {
            case null -> Json.Null.NULL;
            case Boolean b -> Json.bool(b);
            case String s -> Json.str(s);
            case BigDecimal d -> Json.num(d);
            case BigInteger i -> Json.num(new BigDecimal(i));
            case Double d -> finite(d);
            case Float f -> finite(f.doubleValue());
            case Number n -> Json.num(BigDecimal.valueOf(n.longValue()));
            case Map<?, ?> m -> {
                var members = new LinkedHashMap<String, Json>();
                m.forEach((k, v) -> members.put(String.valueOf(k), toJson(v)));
                yield new Json.Obj(members);
            }
            case Iterable<?> it -> {
                var values = new ArrayList<Json>();
                for (Object v : it) values.add(toJson(v));
                yield new Json.Arr(values);
            }
            case Object[] a -> {
                var values = new ArrayList<Json>(a.length);
                for (Object v : a) values.add(toJson(v));
                yield new Json.Arr(values);
            }
            default -> Json.str(String.valueOf(value));
        };
    }

    /// `BigDecimal.valueOf(double)` goes through `Double.toString`, the shortest form that round
    /// trips. JSON has no NaN or infinity, so a non-finite double becomes `null`.
    private static Json finite(double d) {
        return Double.isFinite(d) ? Json.num(BigDecimal.valueOf(d)) : Json.Null.NULL;
    }

    /// `Map<String, Object>` in, [Json.Obj] out — the shape `inputSchema()` returns.
    static Json.Obj toJsonObj(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return Json.Obj.EMPTY;
        var members = new LinkedHashMap<String, Json>();
        map.forEach((k, v) -> members.put(k, toJson(v)));
        return new Json.Obj(members);
    }
}
