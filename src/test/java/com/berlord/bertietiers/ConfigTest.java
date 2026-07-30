package com.berlord.bertietiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.berlord.bertietiers.config.ConfigException;
import com.berlord.bertietiers.config.ConfigParser;
import com.berlord.bertietiers.config.ConfigValidator;
import com.berlord.bertietiers.config.RawConfig;
import com.berlord.bertietiers.config.ValidatedConfig;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parsing, error paths and every validation rule the system promises. */
class ConfigTest {

    private static FakeRegistryProbe probe() {
        return new FakeRegistryProbe()
                .mod("slag")
                .block("minecraft:iron_ore")
                .block("minecraft:gold_ore")
                .block("minecraft:diamond_ore")
                .block("minecraft:coal_ore")
                .blockWithItem("minecraft:redstone_ore", "minecraft:redstone_ore")
                .item("minecraft:stone_pickaxe")
                .item("minecraft:iron_pickaxe")
                .item("minecraft:diamond_pickaxe")
                .item("slag:modular_item")
                .component("slag:modular_type")
                .component("slag:dynamic_parts")
                .transientComponent("minecraft:map_decorations");
    }

    private static ConfigValidator.Result validate(String json) {
        RawConfig raw = ConfigParser.parse(JsonParser.parseString(json));
        return ConfigValidator.validate(raw, probe());
    }

    // ------------------------------------------------------------------ parsing

    @Test
    @DisplayName("a tool may be written as a bare item ID")
    void toolShorthand() {
        ValidatedConfig config = validate("""
                { "tiers": [ { "id": "stone", "level": 200, "tools": [ "minecraft:stone_pickaxe" ],
                               "blocks": [ "minecraft:iron_ore" ] } ] }
                """).config();
        assertEquals(1, config.tiers().size());
        assertEquals("minecraft:stone_pickaxe", config.tiers().get(0).tools().get(0).item());
        assertTrue(config.tiers().get(0).tools().get(0).components().isEmpty());
    }

    @Test
    @DisplayName("errors name the exact JSON path")
    void errorPaths() {
        ConfigException missing = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "level": 200 } ] }
                """));
        assertEquals("tiers[0].id", missing.path());

        ConfigException notInt = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 1.5 } ] }
                """));
        assertEquals("tiers[0].level", notInt.path());

        ConfigException unknownField = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 200, "blokcs": [] } ] }
                """));
        assertEquals("tiers[0].blokcs", unknownField.path());

        ConfigException badBlock = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 200, "blocks": [ "minecraft:iron_ore", "minecraft:nope" ] } ] }
                """));
        assertEquals("tiers[0].blocks[1]", badBlock.path());
        assertTrue(badBlock.getMessage().contains("minecraft:nope"));
    }

    // ------------------------------------------------------------------ validation rules

    @Test
    @DisplayName("tier ids must be unique")
    void duplicateTierId() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 200 }, { "id": "stone", "level": 300 } ] }
                """));
        assertEquals("tiers[1].id", error.path());
    }

    @Test
    @DisplayName("tier levels must be unique")
    void duplicateLevel() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 200 }, { "id": "iron", "level": 200 } ] }
                """));
        assertEquals("tiers[1].level", error.path());
    }

    @Test
    @DisplayName("a block may belong to only one tier")
    void blockInTwoTiers() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [
                    { "id": "stone", "level": 200, "blocks": [ "minecraft:iron_ore" ] },
                    { "id": "iron",  "level": 300, "blocks": [ "minecraft:iron_ore" ] } ] }
                """));
        assertEquals("tiers[1].blocks[0]", error.path());
        assertTrue(error.getMessage().contains("stone"));
    }

    @Test
    @DisplayName("the same tool may not sit in two tiers")
    void toolInTwoTiers() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [
                    { "id": "stone", "level": 200, "tools": [ "minecraft:stone_pickaxe" ] },
                    { "id": "iron",  "level": 300, "tools": [ "minecraft:stone_pickaxe" ] } ] }
                """));
        assertEquals("tiers[1].tools[0]", error.path());
    }

    @Test
    @DisplayName("component predicates that differ in a scalar are accepted as unambiguous")
    void disjointComponentMatchersAreFine() {
        ValidatedConfig config = validate("""
                { "tiers": [
                    { "id": "stone", "level": 200, "tools": [ { "item": "slag:modular_item",
                        "components": { "slag:modular_type": "slag:pickaxe",
                          "slag:dynamic_parts": [ { "components": { "slag:material_type": "slag:stone" } } ] } } ] },
                    { "id": "iron", "level": 300, "tools": [ { "item": "slag:modular_item",
                        "components": { "slag:modular_type": "slag:pickaxe",
                          "slag:dynamic_parts": [ { "components": { "slag:material_type": "slag:iron" } } ] } } ] } ] }
                """).config();
        assertEquals(2, config.tiers().size());
    }

    @Test
    @DisplayName("equally specific matchers that could overlap are rejected")
    void ambiguousComponentMatchers() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [
                    { "id": "stone", "level": 200, "tools": [ { "item": "slag:modular_item",
                        "components": { "slag:modular_type": "slag:pickaxe" } } ] },
                    { "id": "iron", "level": 300, "tools": [ { "item": "slag:modular_item",
                        "components": { "slag:dynamic_parts": [ { "components": { "slag:material_type": "slag:iron" } } ] } } ] } ] }
                """));
        assertEquals("tiers[1].tools[0]", error.path());
    }

    @Test
    @DisplayName("a bare matcher plus a specific one is allowed - the specific one wins")
    void specificityBreaksOverlap() {
        ValidatedConfig config = validate("""
                { "tiers": [
                    { "id": "stone", "level": 200, "tools": [ "slag:modular_item" ] },
                    { "id": "iron",  "level": 300, "tools": [ { "item": "slag:modular_item",
                        "components": { "slag:modular_type": "slag:pickaxe" } } ] } ] }
                """).config();
        assertEquals(0, config.tiers().get(0).tools().get(0).specificity());
        assertEquals(1, config.tiers().get(1).tools().get(0).specificity());
    }

    @Test
    @DisplayName("a transient component can never be matched and is rejected")
    void transientComponentRejected() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 200, "tools": [ { "item": "minecraft:stone_pickaxe",
                    "components": { "minecraft:map_decorations": {} } } ] } ] }
                """));
        assertEquals("tiers[0].tools[0].components.minecraft:map_decorations", error.path());
    }

    @Test
    @DisplayName("an unknown data-component type is rejected")
    void unknownComponentRejected() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 200, "tools": [ { "item": "minecraft:stone_pickaxe",
                    "components": { "slag:not_a_component": 1 } } ] } ] }
                """));
        assertEquals("tiers[0].tools[0].components.slag:not_a_component", error.path());
    }

    @Test
    @DisplayName("exceptions must reference existing tools and blocks")
    void exceptionValidation() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "stone", "level": 200, "blocks": [ "minecraft:iron_ore" ] } ],
                  "exceptions": [ { "tool": "minecraft:not_a_pickaxe", "can_mine": [ "minecraft:iron_ore" ] } ] }
                """));
        assertEquals("exceptions[0].tool.item", error.path());
    }

    @Test
    @DisplayName("an exception on an uncontrolled block warns instead of failing")
    void uselessExceptionWarns() {
        ConfigValidator.Result result = validate("""
                { "tiers": [ { "id": "stone", "level": 200, "tools": [ "minecraft:stone_pickaxe" ],
                               "blocks": [ "minecraft:iron_ore" ] } ],
                  "exceptions": [ { "tool": "minecraft:stone_pickaxe", "can_mine": [ "minecraft:coal_ore" ] } ] }
                """);
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).startsWith("exceptions[0].can_mine[0]"));
    }

    @Test
    @DisplayName("entries from a mod that is not installed are skipped, not fatal")
    void missingModIsSkipped() {
        String json = """
                { "tiers": [
                    { "id": "iron", "level": 300,
                      "tools": [ "minecraft:iron_pickaxe",
                                 { "item": "slag:modular_item", "components": { "slag:modular_type": "slag:pickaxe" } } ],
                      "blocks": [ "minecraft:iron_ore", "someothermod:mythril_ore" ] } ] }
                """;
        RawConfig raw = ConfigParser.parse(JsonParser.parseString(json));
        // no ".mod(\"slag\")" here - Slag and someothermod are both absent
        ConfigValidator.Result result = ConfigValidator.validate(raw, new FakeRegistryProbe()
                .block("minecraft:iron_ore")
                .item("minecraft:iron_pickaxe"));

        ValidatedConfig.Tier tier = result.config().tiers().get(0);
        assertEquals(1, tier.tools().size(), "only the vanilla pickaxe should survive");
        assertEquals(List.of("minecraft:iron_ore"), tier.blocks());
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("slag")));
    }

    @Test
    @DisplayName("a typo inside an installed mod's namespace is still fatal")
    void typoInLoadedNamespaceStillFails() {
        ConfigException error = assertThrows(ConfigException.class, () -> validate("""
                { "tiers": [ { "id": "iron", "level": 300, "tools": [ "slag:modlar_item" ] } ] }
                """));
        assertEquals("tiers[0].tools[0].item", error.path());
    }

    // ------------------------------------------------------------------ editing without recompiling

    @Test
    @DisplayName("a tier can be inserted between two existing ones by editing data only")
    void insertTierInTheMiddle() {
        String before = """
                { "tiers": [
                    { "id": "stone", "level": 200, "tools": [ "minecraft:stone_pickaxe" ],   "blocks": [ "minecraft:iron_ore" ] },
                    { "id": "iron",  "level": 400, "tools": [ "minecraft:diamond_pickaxe" ], "blocks": [ "minecraft:diamond_ore" ] } ] }
                """;
        String after = """
                { "tiers": [
                    { "id": "stone",  "level": 200, "tools": [ "minecraft:stone_pickaxe" ],   "blocks": [ "minecraft:iron_ore" ] },
                    { "id": "bronze", "level": 300, "tools": [ "minecraft:iron_pickaxe" ],    "blocks": [ "minecraft:gold_ore" ] },
                    { "id": "iron",   "level": 400, "tools": [ "minecraft:diamond_pickaxe" ], "blocks": [ "minecraft:diamond_ore" ] } ] }
                """;
        assertEquals(2, validate(before).config().tiers().size());
        ValidatedConfig updated = validate(after).config();
        assertEquals(List.of("stone", "bronze", "iron"),
                updated.tiers().stream().map(ValidatedConfig.Tier::id).toList());
        assertEquals(List.of(200, 300, 400), updated.tiers().stream().map(ValidatedConfig.Tier::level).toList());
    }
}
