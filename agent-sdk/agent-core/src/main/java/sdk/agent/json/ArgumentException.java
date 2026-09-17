package sdk.agent.json;

import java.util.List;
import java.util.stream.Collectors;

/// Tool arguments that could not be validated or bound. Checked, because [sdk.agent.tool.ParamCodec#bind]
/// declares it and the funnel is the one place that must handle it.
///
/// Two shapes: a **schema failure** carries a list of [Violation]s and the raw arguments the model
/// sent, and [#render] produces pi-mono's byte-exact `Validation failed for tool "..."` block — echoing
/// the received arguments back is what lets the model self-correct. A **plain failure** (a
/// `prepareArguments` shim that gave up, for example) carries only a message.
public final class ArgumentException extends Exception {

    /// `path` is the instance path without its leading slash (`edits/0/oldText`), or the missing
    /// property's name, or `""` for the root — exactly what pi renders.
    public record Violation(String path, String message) {
        public static final String ROOT = "";
    }

    private static final String UNKNOWN = "Unknown validation error";

    private final List<Violation> violations;
    private final Json received;
    private final boolean schemaFailure;

    public ArgumentException(String message, Json received) {
        super(message, null, false, false);
        this.violations = List.of();
        this.received = received;
        this.schemaFailure = false;
    }

    public ArgumentException(List<Violation> violations, Json received) {
        super(bullets(violations), null, false, false);
        this.violations = List.copyOf(violations);
        this.received = received;
        this.schemaFailure = true;
    }

    public List<Violation> violations() { return violations; }
    public Json received()              { return received; }
    public boolean isSchemaFailure()    { return schemaFailure; }

    /// The model-facing text. For a schema failure this is pi-mono `ai/utils/validation.ts:82-90`,
    /// byte for byte (MIT — see THIRD-PARTY-NOTICES.md); for a plain failure it is the message.
    public String render(String toolName) {
        if (!schemaFailure) return getMessage();
        return "Validation failed for tool \"%s\":\n%s\n\nReceived arguments:\n%s"
                .formatted(toolName, bullets(violations), received.toPrettyText());
    }

    private static String bullets(List<Violation> violations) {
        if (violations.isEmpty()) return UNKNOWN;
        return violations.stream()
                .map(v -> "  - " + (v.path().isEmpty() ? "root" : v.path()) + ": " + v.message())
                .collect(Collectors.joining("\n"));
    }
}
