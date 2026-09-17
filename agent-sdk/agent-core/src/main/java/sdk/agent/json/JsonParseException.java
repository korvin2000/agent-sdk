package sdk.agent.json;

/// Unchecked: the funnel turns a failed argument parse into a model-facing reprompt, and every
/// other caller parses text it produced itself.
public final class JsonParseException extends RuntimeException {
    private final int position;

    public JsonParseException(String message, int position) {
        super(message + " at offset " + position);
        this.position = position;
    }

    public int position() { return position; }
}
