package com.berlord.bertietiers.command;

import com.berlord.bertietiers.BertieTiers;
import com.berlord.bertietiers.config.ConfigManager;
import com.berlord.bertietiers.logic.Decision;
import com.berlord.bertietiers.logic.MiningAuthority;
import com.berlord.bertietiers.logic.RuntimeConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Operator commands. Reload applies an edited config without restarting; explain answers "why did
 * that not drop" without reading the log.
 */
public final class BertieTiersCommand {
    private BertieTiersCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bertietiers")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("reload").executes(BertieTiersCommand::reload));
        root.then(Commands.literal("status").executes(BertieTiersCommand::status));
        root.then(Commands.literal("dump").executes(BertieTiersCommand::dump));
        root.then(Commands.literal("explain")
                .then(Commands.argument("block", ResourceLocationArgument.id())
                        .suggests((context, builder) ->
                                SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder))
                        .executes(BertieTiersCommand::explain)));

        dispatcher.register(root);
    }

    private static int reload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ConfigManager.LoadResult result = ConfigManager.loadAndInstall();
        if (result.success()) {
            context.getSource().sendSuccess(
                    () -> Component.literal("Bertie Tiers reloaded: " + result.message()).withStyle(ChatFormatting.GREEN),
                    true);
            for (String warning : result.warnings()) {
                context.getSource().sendSuccess(
                        () -> Component.literal("warning: " + warning).withStyle(ChatFormatting.YELLOW), false);
                BertieTiers.LOGGER.warn("Bertie Tiers: {}", warning);
            }
            BertieTiers.LOGGER.info("Bertie Tiers reloaded: {}", result.message());
            return 1;
        }
        context.getSource().sendFailure(Component.literal("Bertie Tiers reload REJECTED: " + result.message()));
        context.getSource().sendFailure(Component.literal("The previously loaded configuration is still active."));
        BertieTiers.LOGGER.error("Bertie Tiers reload rejected: {}", result.message());
        return 0;
    }

    private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        RuntimeConfig config = MiningAuthority.config();
        context.getSource().sendSuccess(() -> Component.literal("Bertie Tiers"), false);
        context.getSource().sendSuccess(() -> Component.literal("  file: " + ConfigManager.configPath()), false);
        context.getSource().sendSuccess(
                () -> Component.literal("  tiers: " + config.source().tiers().size()
                        + ", controlled blocks: " + config.controlledBlockCount()
                        + ", tool matchers: " + config.source().toolMatcherCount()
                        + ", exceptions: " + config.source().exceptions().size()),
                false);
        for (var tier : config.source().tiers()) {
            context.getSource().sendSuccess(
                    () -> Component.literal("  - " + tier.id() + " (level " + tier.level() + "): "
                            + tier.tools().size() + " tool(s), " + tier.blocks().size() + " block(s)"),
                    false);
        }
        return 1;
    }

    private static int dump(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        RuntimeConfig config = MiningAuthority.config();
        Map<Integer, Set<Block>> byLevel = config.blocksByLevel();
        List<Integer> levels = new ArrayList<>(byLevel.keySet());
        levels.sort(Integer::compareTo);
        BertieTiers.LOGGER.info("=== Bertie Tiers: resolved table ===");
        for (var tier : config.source().tiers()) {
            BertieTiers.LOGGER.info("tier '{}' level {}", tier.id(), tier.level());
            for (var tool : tier.tools()) {
                BertieTiers.LOGGER.info("    tool  {}{}", tool.item(),
                        tool.components().isEmpty() ? "" : " " + tool.components());
            }
            for (String blockId : tier.blocks()) {
                BertieTiers.LOGGER.info("    block {}", blockId);
            }
        }
        for (var rule : config.source().exceptions()) {
            BertieTiers.LOGGER.info("exception {} -> {}", rule.tool().item(), rule.blocks());
        }
        BertieTiers.LOGGER.info("=== end ===");
        context.getSource().sendSuccess(
                () -> Component.literal("Bertie Tiers table written to the server log ("
                        + config.controlledBlockCount() + " controlled blocks)."),
                false);
        return 1;
    }

    private static int explain(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(context, "block");
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            context.getSource().sendFailure(Component.literal("unknown block '" + id + "'"));
            return 0;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack tool = player.getMainHandItem();
        RuntimeConfig config = MiningAuthority.config();

        Integer blockLevel = config.blockLevel(block);
        Decision decision = MiningAuthority.decide(tool, block.defaultBlockState(), player.level().registryAccess());
        Integer toolLevel = config.toolLevel(tool, MiningAuthority.ops(player.level().registryAccess()));

        context.getSource().sendSuccess(() -> Component.literal("block  " + id + ": "
                + (blockLevel == null ? "not controlled by Bertie" : "tier level " + blockLevel)), false);
        context.getSource().sendSuccess(() -> Component.literal("tool   "
                + BuiltInRegistries.ITEM.getKey(tool.getItem())
                + (toolLevel == null ? ": in no tier" : ": tier level " + toolLevel)), false);
        ChatFormatting colour = switch (decision) {
            case ALLOW -> ChatFormatting.GREEN;
            case DENY -> ChatFormatting.RED;
            case PASS -> ChatFormatting.GRAY;
        };
        String explanation = switch (decision) {
            case ALLOW -> blockLevel == null
                    ? "ALLOW (not reachable)"
                    : (toolLevel == null || toolLevel < blockLevel
                            ? "ALLOW - granted by a point exception"
                            : "ALLOW - tool level >= block level");
            case DENY -> toolLevel == null
                    ? "DENY - the block is controlled and this tool is in no tier"
                    : "DENY - tool level " + toolLevel + " < block level " + blockLevel;
            case PASS -> "PASS - Bertie does not control this block, original behaviour applies";
        };
        context.getSource().sendSuccess(() -> Component.literal("result " + explanation).withStyle(colour), false);
        return 1;
    }
}
