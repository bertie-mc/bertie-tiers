package com.berlord.bertietiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.berlord.bertietiers.config.JsonMatch;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The component-predicate matching rule. */
class JsonMatchTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    @Test
    @DisplayName("a scalar must be equal")
    void scalars() {
        assertTrue(JsonMatch.matches(json("\"slag:pickaxe\""), json("\"slag:pickaxe\"")));
        assertFalse(JsonMatch.matches(json("\"slag:pickaxe\""), json("\"slag:axe\"")));
        assertTrue(JsonMatch.matches(json("2"), json("2")));
        assertFalse(JsonMatch.matches(json("2"), json("3")));
    }

    @Test
    @DisplayName("extra keys in the actual object are ignored")
    void objectSubset() {
        assertTrue(JsonMatch.matches(json("{\"a\":1}"), json("{\"a\":1,\"b\":2}")));
        assertFalse(JsonMatch.matches(json("{\"a\":1,\"b\":2}"), json("{\"a\":1}")));
        assertFalse(JsonMatch.matches(json("{\"a\":1}"), json("{\"a\":2}")));
    }

    @Test
    @DisplayName("an expected array element only needs to match some actual element")
    void arrayContains() {
        assertTrue(JsonMatch.matches(json("[{\"a\":1}]"), json("[{\"a\":0},{\"a\":1,\"z\":9}]")));
        assertFalse(JsonMatch.matches(json("[{\"a\":1}]"), json("[{\"a\":0},{\"a\":2}]")));
    }

    @Test
    @DisplayName("the shape of a real Slag modular pickaxe is matched by a part predicate")
    void slagShape() {
        // What slag:dynamic_parts encodes to: a list of ItemStack JSON, each with its own components.
        JsonElement actual = json("""
                [
                  { "id": "minecraft:stick", "count": 2 },
                  { "id": "slag:dynamic_part", "count": 1,
                    "components": { "slag:material_type": "slag:iron", "slag:part_type": "slag:pickaxe_head" } }
                ]
                """);
        JsonElement ironHead = json("""
                [ { "components": { "slag:material_type": "slag:iron", "slag:part_type": "slag:pickaxe_head" } } ]
                """);
        JsonElement diamondHead = json("""
                [ { "components": { "slag:material_type": "slag:diamond", "slag:part_type": "slag:pickaxe_head" } } ]
                """);
        assertTrue(JsonMatch.matches(ironHead, actual));
        assertFalse(JsonMatch.matches(diamondHead, actual));
    }

    @Test
    @DisplayName("differing scalars prove two predicates can never both match")
    void disjointness() {
        assertTrue(JsonMatch.provablyDisjoint(json("\"slag:iron\""), json("\"slag:diamond\"")));
        assertFalse(JsonMatch.provablyDisjoint(json("\"slag:iron\""), json("\"slag:iron\"")));
        assertTrue(JsonMatch.provablyDisjoint(json("{\"m\":\"a\",\"p\":\"h\"}"), json("{\"m\":\"b\",\"p\":\"h\"}")));
        assertFalse(JsonMatch.provablyDisjoint(json("{\"m\":\"a\"}"), json("{\"p\":\"h\"}")));
        assertTrue(JsonMatch.provablyDisjoint(json("[{\"m\":\"a\"}]"), json("[{\"m\":\"b\"}]")));
    }

    @Test
    @DisplayName("specificity counts pinned-down leaves")
    void specificity() {
        assertEquals(0, JsonMatch.specificity(json("{}")));
        assertEquals(1, JsonMatch.specificity(json("\"x\"")));
        assertEquals(2, JsonMatch.specificity(json("{\"a\":1,\"b\":\"z\"}")));
        assertEquals(2, JsonMatch.specificity(json("[{\"a\":1,\"b\":2}]")));
    }
}
