package com.berlord.bertietiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.berlord.bertietiers.config.ConfigParser;
import com.berlord.bertietiers.config.ConfigValidator;
import com.berlord.bertietiers.config.RawConfig;
import com.berlord.bertietiers.config.ValidatedConfig;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped {@code default_config.json} itself.
 *
 * <p>The headless GameTest run boots without Slag n' Embers, so its 102 Slag matchers are skipped
 * there and never reach the ambiguity check. This test validates the same file against a probe
 * that <em>does</em> have Slag, which is the only place the full table is actually proven
 * consistent.
 */
class DefaultConfigTest {

    private static final List<String> VANILLA_ORES = List.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:ancient_debris");

    private static final List<String> VANILLA_PICKAXES = List.of(
            "minecraft:wooden_pickaxe", "minecraft:golden_pickaxe", "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe");

    private static RawConfig shipped() {
        try (InputStream in = DefaultConfigTest.class.getResourceAsStream("/bertie_tiers/default_config.json")) {
            assertNotNull(in, "the shipped default config must be on the classpath");
            return ConfigParser.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static FakeRegistryProbe withSlag() {
        FakeRegistryProbe probe = new FakeRegistryProbe()
                .mod("slag")
                .item("slag:modular_item")
                .component("slag:modular_type")
                .component("slag:dynamic_parts");
        VANILLA_ORES.forEach(probe::block);
        VANILLA_PICKAXES.forEach(probe::item);
        return probe;
    }

    @Test
    @DisplayName("the shipped config is valid and unambiguous with Slag n' Embers installed")
    void validWithSlag() {
        ConfigValidator.Result result = ConfigValidator.validate(shipped(), withSlag());
        assertEquals(List.of(), result.warnings(), "the shipped config should load cleanly");

        ValidatedConfig config = result.config();
        assertEquals(List.of("wood", "stone", "iron", "diamond", "netherite"),
                config.tiers().stream().map(ValidatedConfig.Tier::id).toList());
        assertEquals(List.of(100, 200, 300, 400, 500),
                config.tiers().stream().map(ValidatedConfig.Tier::level).toList());
        assertEquals(VANILLA_ORES.size(), config.controlledBlockCount(), "every vanilla ore should be controlled");
        assertEquals(6 + 102, config.toolMatcherCount(), "6 vanilla pickaxes plus 17 Slag materials x 6 shapes");
    }

    @Test
    @DisplayName("without Slag the same file still loads, with the Slag entries skipped")
    void validWithoutSlag() {
        FakeRegistryProbe probe = new FakeRegistryProbe();
        VANILLA_ORES.forEach(probe::block);
        VANILLA_PICKAXES.forEach(probe::item);

        ConfigValidator.Result result = ConfigValidator.validate(shipped(), probe);
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("102"), result.warnings().get(0));
        assertEquals(VANILLA_ORES.size(), result.config().controlledBlockCount());
        assertEquals(6, result.config().toolMatcherCount());
    }

    @Test
    @DisplayName("every vanilla ore sits at the tier vanilla itself requires")
    void oresKeepVanillaTiers() {
        ValidatedConfig config = ConfigValidator.validate(shipped(), withSlag()).config();
        assertEquals(List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore",
                        "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore"),
                blocksOf(config, "wood"));
        assertEquals(List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore",
                        "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
                        "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"),
                blocksOf(config, "stone"));
        assertEquals(List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore",
                        "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
                        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
                        "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"),
                blocksOf(config, "iron"));
        assertEquals(List.of("minecraft:ancient_debris"), blocksOf(config, "diamond"));
        assertEquals(List.of(), blocksOf(config, "netherite"));
    }

    private static List<String> blocksOf(ValidatedConfig config, String tierId) {
        return config.tiers().stream()
                .filter(tier -> tier.id().equals(tierId))
                .findFirst()
                .orElseThrow()
                .blocks();
    }
}
