package sdk.agent.json;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/// The whole JSON model — the only structured value that crosses a public boundary of this SDK.
///
/// Every case is immutable and value-based. [Obj] keeps insertion order because property order is
/// model-visible (a schema's properties are read in order) and byte-stable serialisation is what
/// prompt caching keys on. [Num] holds a [BigDecimal] so a 64-bit id survives a round trip that a
/// `double` would silently round. [Null] is an enum because there is exactly one null and it should
/// be `==`-comparable.
public sealed interface Json permits Json.Null, Json.Bool, Json.Num, Json.Str, Json.Arr, Json.Obj {

    enum Null implements Json { NULL }

    record Bool(boolean value) implements Json {
        public static final Bool TRUE = new Bool(true);
        public static final Bool FALSE = new Bool(false);
    }

    /// Normalised on construction (`1.0` and `1` are the same number), so record equality is
    /// numeric equality.
    record Num(BigDecimal value) implements Json {
        public Num { value = Objects.requireNonNull(value, "value").stripTrailingZeros(); }

        public boolean isIntegral() { return value.scale() <= 0; }
        public long asLong()        { return value.longValueExact(); }
        public int asInt()          { return value.intValueExact(); }
        public double asDouble()    { return value.doubleValue(); }
    }

    record Str(String value) implements Json {
        public Str { Objects.requireNonNull(value, "value"); }
    }

    record Arr(List<Json> values) implements Json {
        public static final Arr EMPTY = new Arr(List.of());
        public Arr { values = List.copyOf(values); }

        public int size()        { return values.size(); }
        public Json get(int i)   { return values.get(i); }
        public boolean isEmpty() { return values.isEmpty(); }
    }

    record Obj(SequencedMap<String, Json> members) implements Json {
        public static final Obj EMPTY = new Obj(new LinkedHashMap<>());
        public Obj { members = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(members)); }

        public Optional<Json> get(String key) { return Optional.ofNullable(members.get(key)); }
        public boolean has(String key)        { return members.containsKey(key); }
        public boolean isEmpty()              { return members.isEmpty(); }

        /// Copy-on-write put; the receiver is untouched.
        public Obj with(String key, Json value) {
            var m = new LinkedHashMap<>(members);
            m.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return new Obj(m);
        }

        public Obj without(String key) {
            if (!members.containsKey(key)) return this;
            var m = new LinkedHashMap<>(members);
            m.remove(key);
            return new Obj(m);
        }
    }

    // ---- factories: the names the rest of the SDK uses ------------------------------------

    static Json nil()              { return Null.NULL; }
    static Str  str(String s)      { return new Str(s); }
    static Num  num(long n)        { return new Num(BigDecimal.valueOf(n)); }
    static Num  num(double d)      { return new Num(BigDecimal.valueOf(d)); }
    static Num  num(BigDecimal d)  { return new Num(d); }
    static Bool bool(boolean b)    { return b ? Bool.TRUE : Bool.FALSE; }
    static Arr  arr(Json... vs)    { return new Arr(List.of(vs)); }
    static Arr  arr(List<? extends Json> vs) { return new Arr(List.copyOf(vs)); }

    /// Alternating `String` key, [Json] value: `Json.obj("a", Json.num(1), "b", Json.str("x"))`.
    static Obj obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("obj(...) needs key/value pairs, got " + kv.length + " arguments");
        }
        var m = new LinkedHashMap<String, Json>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) Objects.requireNonNull(kv[i], "key"), (Json) Objects.requireNonNull(kv[i + 1], "value"));
        }
        return new Obj(m);
    }

    static Obj obj(Map<String, ? extends Json> members) {
        return new Obj(new LinkedHashMap<>(members));
    }

    // ---- text ----------------------------------------------------------------------------

    /// Strict RFC 8259 parse.
    /// @throws JsonParseException on any syntax error, trailing garbage, or nesting deeper than 512
    static Json parse(CharSequence text) { return JsonText.parse(text); }

    /// Compact, canonical text: no whitespace, members in insertion order.
    default String toText()       { return JsonText.write(this, false); }

    /// Two-space pretty text, byte-compatible with JavaScript's `JSON.stringify(x, null, 2)`.
    default String toPrettyText() { return JsonText.write(this, true); }
}
