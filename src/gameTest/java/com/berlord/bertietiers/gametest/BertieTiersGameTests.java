package com.berlord.bertietiers.gametest;

import com.berlord.bertietiers.BertieTiers;
import com.berlord.bertietiers.config.ConfigException;
import com.berlord.bertietiers.config.ConfigParser;
import com.berlord.bertietiers.config.ConfigValidator;
import com.berlord.bertietiers.config.LiveRegistryProbe;
import com.berlord.bertietiers.config.RawConfig;
import com.berlord.bertietiers.config.ValidatedConfig;
import com.berlord.bertietiers.logic.MiningAuthority;
import com.berlord.bertietiers.logic.RuntimeConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Integration proof of the real harvesting path.
 *
 * <p>Each test installs a small throwaway configuration, drives
 * {@code ServerPlayerGameMode#destroyBlock} with a survival {@link FakePlayer} - the same method
 * the server calls when a real player finishes mining - and then looks at what actually landed on
 * the ground. Nothing here re-implements the rules; a passing test means the block really did or
 * really did not drop its loot.
 *
 * <p>Fixture: {@code low} (level 100) = stone pickaxe + iron ore + ancient debris,
 * {@code high} (level 200) = diamond pickaxe + gold ore + diamond ore, plus one point exception
 * letting a stone pickaxe mine diamond ore only. Coal ore is deliberately left out of the config.
 */
@GameTestHolder("bertie_tiers")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = BertieTiers.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BertieTiersGameTests {
    /** Resolved against the holder namespace: {@code data/bertie_tiers/structure/empty.nbt}. */
    private static final String TEMPLATE = "empty";
    private static final BlockPos ORE = new BlockPos(1, 2, 1);

    /** Flips the stand-in "another mod vetoes this block" listener on for one test. */
    private static volatile boolean vetoIronOre = false;
    private static final AtomicBoolean VETO_LISTENER_INSTALLED = new AtomicBoolean();

    private BertieTiersGameTests() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BertieTiersGameTests.class);
    }

    private static final String FIXTURE = """
            {
              "tiers": [
                {
                  "id": "low",
                  "level": 100,
                  "tools": [ { "item": "minecraft:stone_pickaxe" } ],
                  "blocks": [ "minecraft:iron_ore", "minecraft:ancient_debris" ]
                },
                {
                  "id": "high",
                  "level": 200,
                  "tools": [ { "item": "minecraft:diamond_pickaxe" } ],
                  "blocks": [ "minecraft:gold_ore", "minecraft:diamond_ore" ]
                }
              ],
              "exceptions": [
                {
                  "tool": { "item": "minecraft:stone_pickaxe" },
                  "can_mine": [ "minecraft:diamond_ore" ]
                }
              ]
            }
            """;

    // ---------------------------------------------------------------- mandatory checks

    /** A tier-A pickaxe mines a tier-A block. */
    @GameTest(template = TEMPLATE)
    public static void sameTierMines(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.IRON_ORE, new ItemStack(Items.STONE_PICKAXE));
            assertDropped(helper, Items.RAW_IRON, true, "a stone pickaxe should mine tier-low iron ore");
        });
    }

    /** A higher-tier pickaxe mines a lower-tier block. */
    @GameTest(template = TEMPLATE)
    public static void higherTierMinesLower(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.IRON_ORE, new ItemStack(Items.DIAMOND_PICKAXE));
            assertDropped(helper, Items.RAW_IRON, true, "a diamond pickaxe should mine tier-low iron ore");
        });
    }

    /** A lower-tier pickaxe gets no loot from a higher-tier block. */
    @GameTest(template = TEMPLATE)
    public static void lowerTierGetsNothing(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.GOLD_ORE, new ItemStack(Items.STONE_PICKAXE));
            assertDropped(helper, Items.RAW_GOLD, false, "a stone pickaxe must not get tier-high gold ore");
        });
    }

    /** The point exception grants exactly the block it names. */
    @GameTest(template = TEMPLATE)
    public static void exceptionGrantsNamedBlock(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.DIAMOND_ORE, new ItemStack(Items.STONE_PICKAXE));
            assertDropped(helper, Items.DIAMOND, true, "the exception should let a stone pickaxe mine diamond ore");
        });
    }

    /** ...and grants nothing else in that same higher tier. */
    @GameTest(template = TEMPLATE)
    public static void exceptionDoesNotLeak(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.GOLD_ORE, new ItemStack(Items.STONE_PICKAXE));
            assertDropped(helper, Items.RAW_GOLD, false,
                    "the diamond-ore exception must not also unlock gold ore in the same tier");
        });
    }

    /** A tool that is in no tier gets no loot from a controlled block. */
    @GameTest(template = TEMPLATE)
    public static void unassignedToolGetsNothing(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.IRON_ORE, new ItemStack(Items.WOODEN_PICKAXE));
            assertDropped(helper, Items.RAW_IRON, false, "a tool in no tier must not harvest a controlled block");
        });
    }

    /** A block that is not in the config keeps its original behaviour. */
    @GameTest(template = TEMPLATE)
    public static void uncontrolledBlockUnchanged(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.COAL_ORE, new ItemStack(Items.WOODEN_PICKAXE));
            assertDropped(helper, Items.COAL, true, "coal ore is not configured, so vanilla rules must still apply");
        });
    }

    /**
     * Vanilla says a netherite pickaxe harvests iron ore. Bertie never assigned it a tier, so
     * Bertie's refusal has to win over the vanilla tool tags.
     */
    @GameTest(template = TEMPLATE)
    public static void vanillaTagsDoNotWinDeny(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.IRON_ORE, new ItemStack(Items.NETHERITE_PICKAXE));
            assertDropped(helper, Items.RAW_IRON, false, "vanilla's yes must not override Bertie's no");
        });
    }

    /**
     * The other direction: vanilla tags ancient debris {@code needs_diamond_tool}, Bertie put it in
     * the stone tier, so a stone pickaxe has to get the block.
     */
    @GameTest(template = TEMPLATE)
    public static void vanillaTagsDoNotWinAllow(GameTestHelper helper) {
        withFixture(helper, () -> {
            mine(helper, Blocks.ANCIENT_DEBRIS, new ItemStack(Items.STONE_PICKAXE));
            assertDropped(helper, Items.ANCIENT_DEBRIS, true, "vanilla's no must not override Bertie's yes");
        });
    }

    /**
     * Stand-in for "another mod refuses this block unless you hold its own pickaxe": a listener on
     * NeoForge's harvest check that hard-vetoes the block at the highest priority. Bertie's gate
     * runs downstream of the whole event and wins.
     */
    @GameTest(template = TEMPLATE)
    public static void modVetoDoesNotWin(GameTestHelper helper) {
        installVetoListener();
        vetoIronOre = true;
        try {
            withFixture(helper, () -> {
                mine(helper, Blocks.IRON_ORE, new ItemStack(Items.STONE_PICKAXE));
                assertDropped(helper, Items.RAW_IRON, true, "another mod's veto must not beat Bertie's allow");
            });
        } finally {
            vetoIronOre = false;
        }
    }

    /** Allowed mining is ordinary mining: Silk Touch still applies and there is exactly one drop. */
    @GameTest(template = TEMPLATE)
    public static void allowedMiningKeepsSilkTouchAndDoesNotDouble(GameTestHelper helper) {
        withFixture(helper, () -> {
            ItemStack pickaxe = new ItemStack(Items.STONE_PICKAXE);
            pickaxe.enchant(
                    helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH),
                    1);
            mine(helper, Blocks.IRON_ORE, pickaxe);
            assertDropped(helper, Items.IRON_ORE, true, "Silk Touch should still yield the ore block itself");
            assertDropped(helper, Items.RAW_IRON, false, "Silk Touch must not also yield the raw drop");
            int total = drops(helper).size();
            if (total != 1) {
                helper.fail("expected exactly one dropped stack, found " + total + " - drops were duplicated");
            }
        });
    }

    /** A rejected reload must leave the previously loaded configuration running. */
    @GameTest(template = TEMPLATE)
    public static void invalidConfigKeepsPrevious(GameTestHelper helper) {
        withFixture(helper, () -> {
            RuntimeConfig before = MiningAuthority.config();
            boolean threw = false;
            try {
                RawConfig raw = ConfigParser.parse(JsonParser.parseString(
                        "{\"tiers\":[{\"id\":\"broken\",\"level\":1,\"blocks\":[\"minecraft:not_a_real_block\"]}]}"));
                ConfigValidator.validate(raw, LiveRegistryProbe.INSTANCE);
            } catch (ConfigException e) {
                threw = true;
                if (!e.getMessage().contains("not_a_real_block")) {
                    helper.fail("the error should name the offending value, got: " + e.getMessage());
                }
            }
            if (!threw) {
                helper.fail("an unknown block ID should have been rejected");
            }
            if (MiningAuthority.config() != before) {
                helper.fail("a rejected config replaced the running one");
            }
            // ...and the old rules are demonstrably still in force
            mine(helper, Blocks.IRON_ORE, new ItemStack(Items.STONE_PICKAXE));
            assertDropped(helper, Items.RAW_IRON, true, "the previous configuration should still be active");
        });
    }

    /**
     * A tier can be inserted between two existing ones by editing data only. The new middle tier
     * takes gold ore off the top tier, and the pickaxe assigned to it can now mine it.
     */
    @GameTest(template = TEMPLATE)
    public static void tierCanBeInsertedByDataOnly(GameTestHelper helper) {
        RuntimeConfig previous = MiningAuthority.config();
        try {
            install("""
                    {
                      "tiers": [
                        { "id": "low",    "level": 100, "tools": [ "minecraft:stone_pickaxe" ],   "blocks": [ "minecraft:iron_ore" ] },
                        { "id": "middle", "level": 150, "tools": [ "minecraft:golden_pickaxe" ],  "blocks": [ "minecraft:gold_ore" ] },
                        { "id": "high",   "level": 200, "tools": [ "minecraft:diamond_pickaxe" ], "blocks": [ "minecraft:diamond_ore" ] }
                      ]
                    }
                    """);
            mine(helper, Blocks.GOLD_ORE, new ItemStack(Items.GOLDEN_PICKAXE));
            assertDropped(helper, Items.RAW_GOLD, true, "the freshly inserted middle tier should be in force");
            helper.succeed();
        } finally {
            MiningAuthority.install(previous);
        }
    }

    /**
     * Two tools that share one registry ID are told apart by their data components. Slag's
     * {@code slag:modular_item} is the real case; this uses a vanilla component so the test runs
     * without Slag installed, but it exercises the identical predicate and codec path.
     */
    @GameTest(template = TEMPLATE)
    public static void componentPredicateSeparatesSameItem(GameTestHelper helper) {
        RuntimeConfig previous = MiningAuthority.config();
        try {
            install("""
                    {
                      "tiers": [
                        {
                          "id": "low",
                          "level": 100,
                          "tools": [ { "item": "minecraft:stone_pickaxe", "components": { "minecraft:custom_model_data": 1 } } ],
                          "blocks": [ "minecraft:iron_ore" ]
                        },
                        {
                          "id": "high",
                          "level": 200,
                          "tools": [ { "item": "minecraft:stone_pickaxe", "components": { "minecraft:custom_model_data": 2 } } ],
                          "blocks": [ "minecraft:gold_ore" ]
                        }
                      ]
                    }
                    """);

            ItemStack weak = new ItemStack(Items.STONE_PICKAXE);
            weak.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(1));
            mine(helper, Blocks.GOLD_ORE, weak);
            assertDropped(helper, Items.RAW_GOLD, false, "the low-tier variant must not mine the high-tier block");

            ItemStack strong = new ItemStack(Items.STONE_PICKAXE);
            strong.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(2));
            mine(helper, Blocks.GOLD_ORE, strong);
            assertDropped(helper, Items.RAW_GOLD, true, "the high-tier variant of the same item must mine it");
            helper.succeed();
        } finally {
            MiningAuthority.install(previous);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static void withFixture(GameTestHelper helper, Runnable body) {
        RuntimeConfig previous = MiningAuthority.config();
        try {
            install(FIXTURE);
            body.run();
            helper.succeed();
        } finally {
            MiningAuthority.install(previous);
        }
    }

    private static void install(String json) {
        JsonElement root = JsonParser.parseString(json);
        RawConfig raw = ConfigParser.parse(root);
        ValidatedConfig validated = ConfigValidator.validate(raw, LiveRegistryProbe.INSTANCE).config();
        MiningAuthority.install(new RuntimeConfig(validated));
    }

    private static void installVetoListener() {
        if (VETO_LISTENER_INSTALLED.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (PlayerEvent.HarvestCheck event) -> {
                if (vetoIronOre && event.getTargetBlock().is(Blocks.IRON_ORE)) {
                    event.setCanHarvest(false);
                }
            });
        }
    }

    /** Places the block and breaks it through the real survival mining path. */
    private static void mine(GameTestHelper helper, Block block, ItemStack tool) {
        clearDrops(helper);
        helper.setBlock(ORE, block);
        BlockPos absolute = helper.absolutePos(ORE);
        FakePlayer player = miner(helper.getLevel());
        player.setPos(absolute.getX() + 0.5, absolute.getY() + 1.0, absolute.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        if (!player.gameMode.destroyBlock(absolute)) {
            helper.fail("the block was not destroyed at all", ORE);
        }
        helper.assertBlockPresent(Blocks.AIR, ORE);
    }

    private static FakePlayer miner(ServerLevel level) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes("bertie_tiers_gametest".getBytes(StandardCharsets.UTF_8)),
                "bertie_tiers_gametest"));
        player.getInventory().clearContent();
        return player;
    }

    private static void assertDropped(GameTestHelper helper, Item item, boolean expected, String message) {
        List<ItemEntity> found = drops(helper);
        boolean present = found.stream().anyMatch(entity -> entity.getItem().is(item));
        if (present != expected) {
            helper.fail(message + " (expected " + (expected ? "a drop" : "no drop") + " of "
                    + BuiltInRegistries.ITEM.getKey(item) + ", ground had "
                    + found.stream().map(entity -> entity.getItem().toString()).toList() + ")");
        }
    }

    private static List<ItemEntity> drops(GameTestHelper helper) {
        BlockPos absolute = helper.absolutePos(ORE);
        return helper.getLevel().getEntities(EntityType.ITEM, new AABB(absolute).inflate(3.0), entity -> true);
    }

    private static void clearDrops(GameTestHelper helper) {
        drops(helper).forEach(ItemEntity::discard);
    }
}
