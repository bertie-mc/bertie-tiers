package com.berlord.bertietiers.logic;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The single entry point every hook in this mod delegates to. Nothing else is allowed to
 * re-implement the rules: the mixin on the vanilla harvest gate and the NeoForge
 * {@code PlayerEvent.HarvestCheck} listener both call {@link #apply}.
 *
 * <p>Holds the active {@link RuntimeConfig} in a volatile field. Reloading builds a whole new
 * config off-thread and publishes it with a single write, so a mining check either sees the old
 * ruleset or the new one - never a mixture.
 */
public final class MiningAuthority {
    private static volatile RuntimeConfig config = RuntimeConfig.EMPTY;

    private MiningAuthority() {}

    public static void install(RuntimeConfig newConfig) {
        config = newConfig;
    }

    public static RuntimeConfig config() {
        return config;
    }

    /** The verdict for a tool/block pair. {@link Decision#PASS} means "not ours, do not interfere". */
    public static Decision decide(ItemStack tool, BlockState state, HolderLookup.Provider registries) {
        RuntimeConfig active = config;
        Integer blockLevel = active.blockLevel(state.getBlock());
        if (blockLevel == null) {
            return Decision.PASS;
        }
        DynamicOps<JsonElement> ops = ops(registries);
        boolean exceptionAllows = active.exceptionAllows(tool, state.getBlock(), ops);
        Integer toolLevel = active.toolLevel(tool, ops);
        return TierRules.decide(blockLevel, exceptionAllows, toolLevel);
    }

    /**
     * Folds the verdict over whatever vanilla, NeoForge or another mod concluded. Bertie wins for
     * every block it controls; everything else is handed back untouched.
     */
    public static boolean apply(boolean fallback, ItemStack tool, BlockState state, HolderLookup.Provider registries) {
        return switch (decide(tool, state, registries)) {
            case ALLOW -> true;
            case DENY -> false;
            case PASS -> fallback;
        };
    }

    /**
     * Convenience for the hooks: the authoritative answer is always about the player's main hand,
     * because that is the stack vanilla hands to {@code Block#playerDestroy}.
     */
    public static boolean apply(boolean fallback, Player player, BlockState state) {
        return apply(fallback, player.getMainHandItem(), state, player.level().registryAccess());
    }

    public static DynamicOps<JsonElement> ops(HolderLookup.Provider registries) {
        return registries == null ? JsonOps.INSTANCE : RegistryOps.create(JsonOps.INSTANCE, registries);
    }
}
