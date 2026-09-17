package sdk.agent.json;

/// Text ⇄ [Json]. The SDK ships [StandardJsonCodec] and depends on nothing else; a host that
/// prefers another parser implements these three methods over the core-owned tree.
public interface JsonCodec {

    JsonCodec DEFAULT = new StandardJsonCodec();

    /// @throws JsonParseException on any syntax error or trailing content
    Json parse(CharSequence text);

    /// Compact form: no insignificant whitespace.
    String write(Json value);

    /// Two-space indented form, compatible with `JSON.stringify(value, null, 2)`.
    String writePretty(Json value);
}
