package org.watermedia.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link JSONTool}: the Gson binding contract callers rely on (null for null or empty text, a syntax error
 * for malformed text or a tree of another shape) and the lenient path walks and accessors that never throw on
 * missing, null or mistyped values.
 */
@DisplayName("JSONTool")
public class JSONToolTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // ONE KEY PER SHAPE THE ACCESSORS MEET; l NEEDS 54 BITS, SO A DOUBLE ROUND TRIP WOULD LOSE ITS LAST DIGIT
    private static JsonObject sample() {
        return JSONTool.parse("{\"s\":\"x\",\"d\":2.5,\"b\":true,\"i\":7,\"l\":9007199254740993,\"nil\":null,"
                + "\"obj\":{\"k\":1},\"arr\":[1,2],\"word\":\"seven\",\"numText\":\"42\",\"boolText\":\"TRUE\"}", JsonObject.class);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Binding")
    class Binding {

        @Test
        @DisplayName("parse yields null for null and empty text, and write serializes a value")
        void parseBinding() {
            assertNull(JSONTool.parse((String) null, JsonObject.class));
            assertNull(JSONTool.parse("", JsonObject.class));
            assertEquals("{\"a\":1}", JSONTool.write(Map.of("a", 1)));
        }

        @Test
        @DisplayName("parse throws JsonSyntaxException on malformed text or a tree of another shape")
        void parseMalformed() {
            assertThrows(JsonSyntaxException.class, () -> JSONTool.parse("{\"a\":", JsonObject.class));
            assertThrows(JsonSyntaxException.class, () -> JSONTool.parse("[1,2]", JsonObject.class));
            assertEquals(2, JSONTool.parse("[1,2]", JsonArray.class).size());
        }
    }

    @Nested
    @DisplayName("Paths")
    class Paths {

        @Test
        @DisplayName("obj walks a key path to an object and arr reads an array")
        void walk() {
            final JsonObject o = JSONTool.parse("{\"a\":{\"b\":{\"c\":\"x\"}}}", JsonObject.class);
            assertEquals("x", JSONTool.str(JSONTool.obj(o, "a", "b"), "c"));
            assertSame(o, JSONTool.obj(o), "no keys return the root itself");
            assertEquals(1, JSONTool.intOr(JSONTool.obj(sample(), "obj"), "k", 0));
            assertEquals(2, JSONTool.arr(sample(), "arr").size());
            assertEquals(o, JSONTool.obj(JSONTool.parse("[" + o + "]", JsonArray.class).get(0)), "an array element reads as an object");
        }

        @Test
        @DisplayName("a missing, JSON-null or mistyped step yields null instead of throwing")
        void mismatches() {
            final JsonObject o = sample();
            for (final String key: new String[] { "missing", "nil", "arr", "s" })
                assertNull(JSONTool.obj(o, key), key);
            for (final String key: new String[] { "missing", "nil", "obj", "s" })
                assertNull(JSONTool.arr(o, key), key);
            assertNull(JSONTool.obj(o, "obj", "k"), "a number in the middle of the path");
            assertNull(JSONTool.obj(o, "missing", "k"), "a missing step in the middle of the path");
            assertNull(JSONTool.obj(null, "obj"));
            assertNull(JSONTool.obj(JsonNull.INSTANCE));
            assertNull(JSONTool.arr(null, "arr"));
            assertNull(JSONTool.str(JSONTool.obj(o, "missing"), "k"));
        }
    }

    @Nested
    @DisplayName("Accessors")
    class Accessors {

        @Test
        @DisplayName("typed accessors read present keys")
        void typedAccessors() {
            final JsonObject o = sample();
            assertEquals("x", JSONTool.str(o, "s"));
            assertEquals(2.5, JSONTool.num(o, "d"));
            assertTrue(JSONTool.bool(o, "b"));
            assertEquals(7, JSONTool.intOr(o, "i", 0));
            assertEquals(9_007_199_254_740_993L, JSONTool.longOr(o, "l", 0L));
        }

        @Test
        @DisplayName("numeric and boolean strings are read as their value, and numbers read as text")
        void textValues() {
            final JsonObject o = sample();
            assertEquals(42d, JSONTool.num(o, "numText"));
            assertEquals(42, JSONTool.intOr(o, "numText", 0));
            assertEquals(42L, JSONTool.longOr(o, "numText", 0L));
            assertTrue(JSONTool.bool(o, "boolText"));
            assertEquals("7", JSONTool.str(o, "i"));
        }

        @Test
        @DisplayName("absent or JSON-null keys and a null object fall back to their default")
        void missingKeys() {
            final JsonObject o = sample();
            for (final String key: new String[] { "missing", "nil" }) {
                assertNull(JSONTool.str(o, key), key);
                assertEquals(0d, JSONTool.num(o, key), key);
                assertFalse(JSONTool.bool(o, key), key);
                assertEquals(9, JSONTool.intOr(o, key, 9), key);
                assertEquals(9L, JSONTool.longOr(o, key, 9L), key);
            }
            assertNull(JSONTool.str(null, "s"));
            assertEquals(3, JSONTool.intOr(null, "i", 3));
        }

        @Test
        @DisplayName("objects, arrays, booleans and non-numeric text fall back instead of throwing")
        void wrongTypes() {
            final JsonObject o = sample();
            for (final String key: new String[] { "obj", "arr" }) {
                assertNull(JSONTool.str(o, key), key);
                assertEquals(0d, JSONTool.num(o, key), key);
                assertFalse(JSONTool.bool(o, key), key);
                assertEquals(-1, JSONTool.intOr(o, key, -1), key);
                assertEquals(-1L, JSONTool.longOr(o, key, -1L), key);
            }
            assertEquals(0d, JSONTool.num(o, "word"));
            assertEquals(-1, JSONTool.intOr(o, "word", -1));
            assertEquals(-1L, JSONTool.longOr(o, "word", -1L));
            assertEquals(0d, JSONTool.num(o, "b"), "a boolean is no number");
            assertFalse(JSONTool.bool(o, "word"));
        }
    }
}
