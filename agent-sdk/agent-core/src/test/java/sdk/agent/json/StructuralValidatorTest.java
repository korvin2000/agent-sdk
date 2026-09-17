package sdk.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class StructuralValidatorTest {

    public record ReadParams(String path, @Constraint(min = 1) Optional<Integer> offset, @Constraint(min = 1, max = 5000) Optional<Integer> limit) { }

    public record EditParams(String path, @Constraint(minItems = 1) List<Edit> edits) {
        public record Edit(String oldText, String newText) { }
    }

    public enum Level { LOW, HIGH }

    public record Flags(Level level, boolean verbose, List<String> tags) { }

    private static final Json.Obj READ = JsonSchema.ofRecord(ReadParams.class);

    @Test
    void rendersPisValidationBlockByteExact() {
        Json raw = Json.parse("{\"offset\":0,\"limit\":1.5,\"extra\":true}");
        ArgumentException e = assertThrows(ArgumentException.class, () -> StructuralValidator.validate(READ, raw));
        String expected = """
                Validation failed for tool "read":
                  - path: must have required property 'path'
                  - offset: must be >= 1
                  - limit: must be integer
                  - root: must NOT have additional properties

                Received arguments:
                {
                  "offset": 0,
                  "limit": 1.5,
                  "extra": true
                }""";
        assertEquals(expected, e.render("read"));
    }

    @Test
    void coercesStringsToNumbersAndScalarsToArrays() throws ArgumentException {
        Json coerced = StructuralValidator.validate(READ, Json.parse("{\"path\":\"a\",\"offset\":\"5\"}"));
        assertEquals(Json.parse("{\"path\":\"a\",\"offset\":5}"), coerced);

        Json.Obj flags = JsonSchema.ofRecord(Flags.class);
        Json tags = StructuralValidator.validate(flags, Json.parse("{\"level\":\"low\",\"verbose\":\"true\",\"tags\":\"one\"}"));
        assertEquals(Json.parse("{\"level\":\"low\",\"verbose\":true,\"tags\":[\"one\"]}"), tags);
    }

    @Test
    void reportsNestedPathsAndMinItems() {
        Json.Obj edit = JsonSchema.ofRecord(EditParams.class);
        ArgumentException empty = assertThrows(ArgumentException.class, () -> StructuralValidator.validate(edit, Json.parse("{\"path\":\"a\",\"edits\":[]}")));
        assertEquals(List.of(new ArgumentException.Violation("edits", "must NOT have fewer than 1 items")), empty.violations());

        ArgumentException nested = assertThrows(ArgumentException.class, () -> StructuralValidator.validate(edit, Json.parse("{\"path\":\"a\",\"edits\":[{\"oldText\":\"x\"}]}")));
        assertEquals(List.of(new ArgumentException.Violation("edits/0", "must have required property 'newText'")), nested.violations());
    }

    @Test
    void rejectsUnknownEnumValues() {
        Json.Obj flags = JsonSchema.ofRecord(Flags.class);
        ArgumentException e = assertThrows(ArgumentException.class, () -> StructuralValidator.validate(flags, Json.parse("{\"level\":\"medium\",\"verbose\":true,\"tags\":[]}")));
        assertEquals("must be equal to one of the allowed values", e.violations().getFirst().message());
        assertEquals("level", e.violations().getFirst().path());
    }

    @Test
    void reportsUnknownValidationErrorWhenThereAreNoViolations() {
        assertEquals("Validation failed for tool \"x\":\nUnknown validation error\n\nReceived arguments:\n{}",
                new ArgumentException(List.of(), Json.Obj.EMPTY).render("x"));
    }
}
