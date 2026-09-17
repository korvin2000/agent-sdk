package sdk.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class JsonSchemaTest {

    public record ReadParams(
            @Doc("Path to the file to read (relative or absolute)") String path,
            @Doc("Line number to start reading from (1-indexed)") @Constraint(min = 1) Optional<Integer> offset,
            @Doc("Maximum number of lines to read") @Constraint(min = 1) Optional<Integer> limit) { }

    public record EditParams(String path, @Constraint(minItems = 1) List<Edit> edits) {
        public record Edit(@Doc("Exact text") String oldText, String newText) { }
    }

    public enum Status { PENDING, IN_PROGRESS, COMPLETED }

    public record Misc(Status status, Map<String, Integer> counts, double ratio, boolean flag, Json anything, int[] ids) { }

    @Test
    void derivesTheReadSchemaByteStable() {
        String expected = "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"Path to the file to read (relative or absolute)\"},"
                + "\"offset\":{\"type\":\"integer\",\"description\":\"Line number to start reading from (1-indexed)\",\"minimum\":1},"
                + "\"limit\":{\"type\":\"integer\",\"description\":\"Maximum number of lines to read\",\"minimum\":1}},"
                + "\"required\":[\"path\"],\"additionalProperties\":false}";
        assertEquals(expected, JsonSchema.ofRecord(ReadParams.class).toText());
    }

    @Test
    void inlinesNestedRecordsAndEmitsMinItems() {
        Json.Obj schema = JsonSchema.ofRecord(EditParams.class);
        Json.Obj edits = (Json.Obj) ((Json.Obj) schema.get("properties").orElseThrow()).get("edits").orElseThrow();
        assertEquals(Json.num(1), edits.get("minItems").orElseThrow());
        Json.Obj items = (Json.Obj) edits.get("items").orElseThrow();
        assertEquals(Json.Bool.FALSE, items.get("additionalProperties").orElseThrow());
        assertEquals(Json.arr(Json.str("oldText"), Json.str("newText")), items.get("required").orElseThrow());
    }

    @Test
    void mapsEnumsMapsNumbersBooleansAnyAndArrays() {
        Json.Obj props = (Json.Obj) JsonSchema.ofRecord(Misc.class).get("properties").orElseThrow();
        assertEquals(Json.parse("{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\"]}"), props.get("status").orElseThrow());
        assertEquals(Json.parse("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}"), props.get("counts").orElseThrow());
        assertEquals(Json.parse("{\"type\":\"number\"}"), props.get("ratio").orElseThrow());
        assertEquals(Json.parse("{\"type\":\"boolean\"}"), props.get("flag").orElseThrow());
        assertEquals(Json.Obj.EMPTY, props.get("anything").orElseThrow());
        assertEquals(Json.parse("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}"), props.get("ids").orElseThrow());
    }

    @Test
    void cachesPerClass() {
        assertSame(JsonSchema.ofRecord(ReadParams.class), JsonSchema.ofRecord(ReadParams.class));
    }

    public record BadConstraint(@Constraint(min = 1) String name) { }

    @Test
    void rejectsAConstraintOnAnIncompatibleType() {
        assertThrows(IllegalArgumentException.class, () -> JsonSchema.ofRecord(BadConstraint.class));
    }
}
