package sdk.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sdk.agent.tool.ParamCodec;

class ParamBinderTest {

    public enum Status { PENDING, IN_PROGRESS }

    public record Item(String text, Status status) { }

    public record Params(String path, Optional<Integer> offset, long count, double ratio, BigDecimal exact, boolean flag,
                         List<Item> items, Set<String> tags, Map<String, Integer> counts, int[] ids, Json any, Optional<Item> maybe) { }

    @Test
    void bindsEveryShapeThroughTheSchemaItDerives() throws ArgumentException {
        Json raw = Json.parse("""
                {"path":"a.txt","offset":"7","count":12345678901,"ratio":0.5,"exact":1.25,"flag":true,
                 "items":[{"text":"x","status":"in_progress"}],"tags":["a","a","b"],"counts":{"k":2},"ids":[1,2],
                 "any":{"free":[1]}}""");
        Params p = ParamCodec.ofRecord(Params.class).bind(raw);
        assertEquals("a.txt", p.path());
        assertEquals(Optional.of(7), p.offset());
        assertEquals(12345678901L, p.count());
        assertEquals(0.5, p.ratio());
        assertEquals(new BigDecimal("1.25"), p.exact());
        assertEquals(List.of(new Item("x", Status.IN_PROGRESS)), p.items());
        assertEquals(Set.of("a", "b"), p.tags());
        assertEquals(Map.of("k", 2), p.counts());
        assertEquals(2, p.ids().length);
        assertEquals(Json.parse("{\"free\":[1]}"), p.any());
        assertEquals(Optional.empty(), p.maybe());
    }

    @Test
    void reportsTypeMismatchesAtAjvPaths() {
        ArgumentException e = assertThrows(ArgumentException.class, () -> ParamBinder.bind(Params.class,
                Json.parse("{\"path\":1,\"count\":1,\"ratio\":1,\"exact\":1,\"flag\":true,\"items\":[{\"text\":\"x\",\"status\":\"nope\"}],\"tags\":[],\"counts\":{},\"ids\":[],\"any\":1}")));
        assertEquals(List.of(new ArgumentException.Violation("path", "must be string"),
                             new ArgumentException.Violation("items/0/status", "must be equal to one of the allowed values")),
                e.violations());
    }

    @Test
    void reportsMissingRequiredProperties() {
        ArgumentException e = assertThrows(ArgumentException.class, () -> ParamBinder.bind(Item.class, Json.parse("{}")));
        assertEquals(List.of(new ArgumentException.Violation("text", "must have required property 'text'"),
                             new ArgumentException.Violation("status", "must have required property 'status'")),
                e.violations());
    }

    public record Constrained(@Constraint(min = 1) int n) { }

    @Test
    void rejectsNonIntegralNumbersForIntegers() {
        ArgumentException e = assertThrows(ArgumentException.class, () -> ParamBinder.bind(Constrained.class, Json.parse("{\"n\":1.5}")));
        assertEquals(List.of(new ArgumentException.Violation("n", "must be integer")), e.violations());
    }

    @Test
    void codecValidatesBeforeBinding() {
        ArgumentException e = assertThrows(ArgumentException.class, () -> ParamCodec.ofRecord(Constrained.class).bind(Json.parse("{\"n\":0}")));
        assertEquals("  - n: must be >= 1", e.getMessage());
    }
}
