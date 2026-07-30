package com.berlord.bertietiers.mixin;

import com.berlord.bertietiers.logic.MiningAuthority;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The one place a survival player's drops are decided.
 *
 * <p>{@code ServerPlayerGameMode#destroyBlock} computes
 * {@code boolean flag1 = blockstate.canHarvestBlock(level, pos, player)} and then uses that single
 * value twice: it is handed to {@code BlockState#onDestroyedByPlayer} as {@code willHarvest}, and
 * it gates the {@code Block#playerDestroy} call that actually rolls the loot table. Redirecting
 * that one invocation is therefore the narrowest point that makes Bertie's verdict final, and it
 * keeps both consumers consistent.
 *
 * <p>It also sits strictly downstream of everything that could disagree: NeoForge's
 * {@code PlayerEvent.HarvestCheck}, a mod's own {@code Block#canHarvestBlock} override (which
 * never fires that event), the vanilla {@code Tool} component and {@code #incorrect_for_*_tool}
 * tags, and Slag n' Embers' {@code ModularToolsItem#isCorrectToolForDrops} - all of them feed into
 * this call, so replacing its result outranks all of them.
 *
 * <p>Nothing else changes: the block still breaks with its normal animation and speed, the loot
 * table, Fortune, Silk Touch, XP and tool damage are untouched, and blocks the config does not
 * list get the original value back verbatim.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {

    @Redirect(
            method = "destroyBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;canHarvestBlock(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)Z"
            )
    )
    private boolean bertie_tiers$authoritativeHarvestCheck(BlockState state, BlockGetter level, BlockPos pos, Player player) {
        // Run the original check first so mods that only hook it still see their callback, and so
        // an unlisted block gets exactly the answer it would have had without this mod.
        boolean original = state.canHarvestBlock(level, pos, player);
        return MiningAuthority.apply(original, player, state);
    }
}
