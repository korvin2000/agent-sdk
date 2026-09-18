package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sdk.agent.json.Json;

class McpJsonBridgeTest {

    @Test
    void roundTripsNestedObjectsAndArrays() {
        Json original = Json.parse("""
                {"a":{"b":[1,2,{"c":"d"}],"e":{}},"f":[],"g":[[true,false]]}""");

        Object sdk = McpJsonBridge.toSdk(original);
        assertInstanceOf(Map.class, sdk);
        assertEquals(original, McpJsonBridge.toJson(sdk));
    }

    @Test
    void preservesMemberOrderInBothDirections() {
        Json original = Json.parse("""
                {"z":1,"a":2,"m":3}""");

        Json back = McpJsonBridge.toJson(McpJsonBridge.toSdk(original));

        assertEquals("""
                {"z":1,"a":2,"m":3}""", back.toText());
        assertEquals(List.of("z", "a", "m"), new ArrayList<>(McpJsonBridge.toMap(original).keySet()));
    }

    @Test
    void keepsIntegersIntegralAndDecimalsExact() {
        Map<String, Object> out = McpJsonBridge.toMap(Json.parse("""
                {"small":7,"id":9007199254740993,"huge":123456789012345678901234567890,"rate":0.1}"""));

        assertEquals(7L, out.get("small"));
        assertEquals(9007199254740993L, out.get("id"));                    // a double would round this
        assertEquals(new BigInteger("123456789012345678901234567890"), out.get("huge"));
        assertEquals(new BigDecimal("0.1"), out.get("rate"));
    }

    @Test
    void acceptsEveryNumberTypeJacksonProducesForAnUntypedValue() {
        var map = new LinkedHashMap<String, Object>();
        map.put("i", 7);                                                   // Integer
        map.put("l", 9007199254740993L);                                   // Long
        map.put("bi", new BigInteger("123456789012345678901234567890"));
        map.put("d", 0.25d);                                               // Double
        map.put("f", 0.5f);                                                // Float
        map.put("bd", new BigDecimal("1.750"));

        Json json = McpJsonBridge.toJsonObj(map);

        assertEquals("""
                {"i":7,"l":9007199254740993,"bi":123456789012345678901234567890,\
                "d":0.25,"f":0.5,"bd":1.75}""", json.toText());
    }

    @Test
    void mapsNullBooleansAndStrings() {
        Json original = Json.parse("""
                {"n":null,"t":true,"f":false,"s":"x"}""");
        Map<String, Object> out = McpJsonBridge.toMap(original);

        assertTrue(out.containsKey("n"));
        assertNull(out.get("n"));
        assertEquals(Boolean.TRUE, out.get("t"));
        assertEquals(Boolean.FALSE, out.get("f"));
        assertEquals("x", out.get("s"));
        assertEquals(original, McpJsonBridge.toJson(out));
    }

    @Test
    void integralValuesLeaveAsLongNotAsNegativeScaleBigDecimal() {
        // Json.Num strips trailing zeros, so 1000 becomes 1E+3 — which Jackson would write as
        // "1E+3", valid JSON but not what a server expects to read back as an integer.
        assertEquals(1000L, McpJsonBridge.toSdk(Json.num(1000)));
        assertEquals(Json.num(1000), McpJsonBridge.toJson(McpJsonBridge.toSdk(Json.num(1000))));
    }

    @Test
    void degradesNonJsonValuesVisiblyRatherThanDropping() {
        assertEquals(Json.Null.NULL, McpJsonBridge.toJson(Double.NaN));
        assertEquals(Json.Null.NULL, McpJsonBridge.toJson(Double.POSITIVE_INFINITY));
        assertEquals(Json.str("ABC"), McpJsonBridge.toJson(new StringBuilder("ABC")));
        assertEquals(Json.parse("[1,2]"), McpJsonBridge.toJson(new Object[] {1, 2}));
    }

    @Test
    void nonObjectArgumentsAreRejectedInsteadOfSilentlyDiscarded() {
        var scalar = assertThrows(IllegalArgumentException.class,
                () -> McpJsonBridge.toMap(Json.str("not an object")));
        assertEquals("MCP tool arguments must be a JSON object", scalar.getMessage());
        assertThrows(IllegalArgumentException.class, () -> McpJsonBridge.toMap(Json.Null.NULL));
        assertEquals(Json.Obj.EMPTY, McpJsonBridge.toJsonObj(null));
    }
}
