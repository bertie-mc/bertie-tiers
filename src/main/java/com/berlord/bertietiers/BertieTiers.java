package com.berlord.bertietiers;

import com.berlord.bertietiers.command.BertieTiersCommand;
import com.berlord.bertietiers.config.ConfigManager;
import com.berlord.bertietiers.gametest.BertieTiersGameTests;
import com.berlord.bertietiers.logic.MiningAuthority;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

/**
 * Bertie Tiers - one authoritative, data-driven mining-tier system for the Bertie modpack.
 *
 * <p>Everything lives in {@code config/bertie_tiers.json}: named tiers with a numeric level, the
 * tools at that level and the blocks that require it, plus point {@code tool -> block} exceptions.
 * For any block listed there, Bertie's answer is final; it overrides vanilla tool tags, another
 * mod's mining level, a parallel tier system such as Slag n' Embers, and any "only this pickaxe
 * works" check. Blocks that are not listed are left completely alone.
 *
 * <p>Two hooks, one brain. {@link com.berlord.bertietiers.mixin.ServerPlayerGameModeMixin} sits on
 * the vanilla harvest gate that decides whether {@code Block#playerDestroy} runs, and the
 * {@link PlayerEvent.HarvestCheck} listener below covers callers that ask the NeoForge event
 * directly. Both delegate to {@link MiningAuthority} - the rules are never duplicated.
 */
@Mod(BertieTiers.MOD_ID)
public class BertieTiers {
    public static final String MOD_ID = "bertie_tiers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BertieTiers(IEventBus modEventBus) {
        modEventBus.addListener(BertieTiers::registerGameTests);
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(BertieTiersGameTests.class);
    }

    /** Game-bus listeners (the default bus). The whole system is logical-server only. */
    @EventBusSubscriber(modid = MOD_ID)
    public static final class ServerHooks {
        private ServerHooks() {}

        /**
         * Loaded once per server start - after registries are frozen, before any world is ticking.
         * A broken file logs loudly and leaves the system inert rather than aborting the launch.
         */
        @SubscribeEvent
        public static void onServerAboutToStart(ServerAboutToStartEvent event) {
            ConfigManager.LoadResult result = ConfigManager.loadAndInstall();
            if (result.success()) {
                LOGGER.info("Bertie Tiers: {}", result.message());
                result.warnings().forEach(warning -> LOGGER.warn("Bertie Tiers: {}", warning));
            } else {
                MiningAuthority.install(com.berlord.bertietiers.logic.RuntimeConfig.EMPTY);
                LOGGER.error("Bertie Tiers: {}", result.message());
                LOGGER.error("Bertie Tiers: no tier rules are active. Fix {} and run /bertietiers reload.",
                        ConfigManager.configPath());
            }
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            BertieTiersCommand.register(event.getDispatcher());
        }

        /**
         * Secondary hook. NeoForge fires this from the default {@code Block#canHarvestBlock}, so it
         * covers callers that consult the harvest check outside the vanilla break path. Runs last
         * so nothing can overwrite the verdict, and only on the logical server - the client is
         * never asked to decide who gets loot.
         */
        @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
        public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
            if (event.getEntity().level().isClientSide()) {
                return;
            }
            event.setCanHarvest(MiningAuthority.apply(event.canHarvest(), event.getEntity(), event.getTargetBlock()));
        }
    }
}
