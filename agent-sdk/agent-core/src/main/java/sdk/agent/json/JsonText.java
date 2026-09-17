package sdk.agent.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/// A strict RFC 8259 parser and writer with no dependencies. Nesting is capped at 512 levels so a
/// hostile document cannot blow the stack; strings round-trip every escape including surrogate
/// pairs; numbers are kept exact.
final class JsonText {

    private static final int MAX_DEPTH = 512;

    private JsonText() { }

    static Json parse(CharSequence text) {
        var p = new Parser(text);
        p.skipWs();
        Json value = p.value(0);
        p.skipWs();
        if (p.pos != p.text.length()) throw p.error("unexpected trailing content");
        return value;
    }

    /// Compact, or two-space indented like `JSON.stringify(value, null, 2)`.
    static String write(Json value, boolean pretty) {
        var sb = new StringBuilder(pretty ? 128 : 64);
        write(sb, value, pretty ? 2 : -1, 0);
        return sb.toString();
    }

    // ---- writer ------------------------------------------------------------------------------

    private static void write(StringBuilder sb, Json value, int indent, int depth) {
        switch (value) {
            case Json.Null _ -> sb.append("null");
            case Json.Bool b -> sb.append(b.value());
            case Json.Num n  -> sb.append(n.value().toPlainString());
            case Json.Str s  -> quote(sb, s.value());
            case Json.Arr a  -> {
                if (a.isEmpty()) { sb.append("[]"); return; }
                sb.append('[');
                boolean first = true;
                for (Json v : a.values()) {
                    if (!first) sb.append(',');
                    first = false;
                    newline(sb, indent, depth + 1);
                    write(sb, v, indent, depth + 1);
                }
                newline(sb, indent, depth);
                sb.append(']');
            }
            case Json.Obj o  -> {
                if (o.isEmpty()) { sb.append("{}"); return; }
                sb.append('{');
                boolean first = true;
                for (var e : o.members().entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    newline(sb, indent, depth + 1);
                    quote(sb, e.getKey());
                    sb.append(indent < 0 ? ":" : ": ");
                    write(sb, e.getValue(), indent, depth + 1);
                }
                newline(sb, indent, depth);
                sb.append('}');
            }
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n');
        sb.repeat(' ', indent * depth);
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || isLoneSurrogate(s, i)) unicodeEscape(sb, c);
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static boolean isLoneSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        if (Character.isLowSurrogate(c))  return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        return false;
    }

    private static void unicodeEscape(StringBuilder sb, char c) {
        sb.append("\\u");
        String hex = Integer.toHexString(c);
        sb.repeat('0', 4 - hex.length());
        sb.append(hex);
    }

    // ---- parser ------------------------------------------------------------------------------

    private static final class Parser {
        final CharSequence text;
        int pos;

        Parser(CharSequence text) { this.text = text; }

        Json value(int depth) {
            if (depth > MAX_DEPTH) throw error("nesting deeper than " + MAX_DEPTH);
            if (pos >= text.length()) throw error("unexpected end of input");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> new Json.Str(string());
                case 't' -> literal("true", Json.Bool.TRUE);
                case 'f' -> literal("false", Json.Bool.FALSE);
                case 'n' -> literal("null", Json.Null.NULL);
                default  -> {
                    if (c == '-' || isDigit(c)) yield number();
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Json object(int depth) {
            pos++;                                     // '{'
            var members = new LinkedHashMap<String, Json>();
            skipWs();
            if (peek() == '}') { pos++; return new Json.Obj(members); }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("expected a string key");
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                members.put(key, value(depth + 1));
                skipWs();
                char c = next();
                if (c == '}') return new Json.Obj(members);
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        private Json array(int depth) {
            pos++;                                     // '['
            var values = new ArrayList<Json>();
            skipWs();
            if (peek() == ']') { pos++; return new Json.Arr(values); }
            while (true) {
                skipWs();
                values.add(value(depth + 1));
                skipWs();
                char c = next();
                if (c == ']') return new Json.Arr(values);
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        private String string() {
            pos++;                                     // opening quote
            var sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) throw error("unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= text.length()) throw error("unterminated escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'u'  -> sb.append(hex4());
                        default   -> throw error("invalid escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private char hex4() {
            if (pos + 4 > text.length()) throw error("truncated \\u escape");
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(text.charAt(pos + i), 16);
                if (d < 0) throw error("invalid hex digit in \\u escape");
                v = (v << 4) | d;
            }
            pos += 4;
            return (char) v;
        }

        private Json number() {
            int start = pos;
            if (peek() == '-') pos++;
            if (peek() == '0') pos++;
            else if (peek() >= '1' && peek() <= '9') digits();
            else throw error("invalid number");
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) throw error("invalid number: digits expected after '.'");
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                if (!isDigit(peek())) throw error("invalid number: exponent digits expected");
                digits();
            }
            return new Json.Num(new BigDecimal(text.subSequence(start, pos).toString()));
        }

        private void digits() { while (isDigit(peek())) pos++; }

        private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

        private Json literal(String word, Json value) {
            int end = pos + word.length();
            if (end > text.length() || !text.subSequence(pos, end).toString().equals(word)) {
                throw error("invalid literal");
            }
            pos = end;
            return value;
        }

        void skipWs() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else return;
            }
        }

        /// `\0` at end of input — every caller treats it as "not what I wanted".
        private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }

        private char next() {
            if (pos >= text.length()) throw error("unexpected end of input");
            return text.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) throw error("expected '" + c + "'");
        }

        JsonParseException error(String message) { return new JsonParseException(message, pos); }
    }
}
