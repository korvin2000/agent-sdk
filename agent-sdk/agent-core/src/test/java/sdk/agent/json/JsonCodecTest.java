package sdk.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCodecTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "null", "true", "false", "0", "-1", "1.5", "1e5", "\"\"",
            "{\"a\":1,\"b\":[1,2,{\"c\":null}],\"d\":\"x\\ny\"}",
            "\"quote\\\" backslash\\\\ tab\\t\"",
            "\"\\ud83d\\ude00\"",                        // surrogate pair escape
            "[[[[[[[[[[1]]]]]]]]]]"
    })
    void roundTripsCompactText(String text) {
        Json parsed = Json.parse(text);
        assertEquals(parsed, Json.parse(parsed.toText()));
    }

    @Test
    void keepsMemberOrderAndCompactShape() {
        Json j = Json.obj("z", Json.num(1), "a", Json.arr(Json.str("x"), Json.bool(true), Json.nil()));
        assertEquals("{\"z\":1,\"a\":[\"x\",true,null]}", j.toText());
    }

    @Test
    void prettyPrintsLikeJsonStringify() {
        Json j = Json.obj("path", Json.str("a.txt"), "edits", Json.arr(Json.obj("oldText", Json.str("x"))), "empty", Json.Obj.EMPTY, "list", Json.Arr.EMPTY);
        String expected = """
                {
                  "path": "a.txt",
                  "edits": [
                    {
                      "oldText": "x"
                    }
                  ],
                  "empty": {},
                  "list": []
                }""";
        assertEquals(expected, j.toPrettyText());
    }

    @Test
    void numbersAreExactAndNormalised() {
        assertEquals(Json.num(1), Json.parse("1.0"));
        assertEquals(new Json.Num(new BigDecimal("12345678901234567890")), Json.parse("12345678901234567890"));
        assertEquals("100000", Json.parse("1e5").toText());
        assertEquals("0.1", Json.parse("0.10").toText());
        assertEquals(true, ((Json.Num) Json.parse("100")).isIntegral());
        assertEquals(false, ((Json.Num) Json.parse("1.5")).isIntegral());
    }

    @Test
    void escapesControlCharactersAndLoneSurrogates() {
        assertEquals("\"a\\u0001b\\u0007\"", Json.str("a\u0001b\u0007").toText());
        assertEquals("\"\\ud83d\"", Json.str("\ud83d").toText());
        assertEquals("\"\ud83d\ude00\"", Json.str("\ud83d\ude00").toText());
        assertEquals("\"\u00e9\u2014\"", Json.str("\u00e9\u2014").toText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[1,]", "{\"a\":}", "01", "1.", "-", "\"abc", "\"\\x\"", "tru", "{\"a\":1} x", "\"a\u0001\"", "[1 2]"})
    void rejectsMalformedText(String text) {
        assertThrows(JsonParseException.class, () -> Json.parse(text));
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(600) + "]".repeat(600);
        assertThrows(JsonParseException.class, () -> Json.parse(deep));
    }

    @Test
    void nullIsASingleton() {
        assertSame(Json.Null.NULL, Json.parse("null"));
        assertSame(Json.Null.NULL, Json.nil());
    }

    @Test
    void objFactoryValidatesPairs() {
        assertThrows(IllegalArgumentException.class, () -> Json.obj("a"));
        assertEquals(List.of("a", "b"), List.copyOf(Json.obj("a", Json.num(1), "b", Json.num(2)).members().keySet()));
    }
}
