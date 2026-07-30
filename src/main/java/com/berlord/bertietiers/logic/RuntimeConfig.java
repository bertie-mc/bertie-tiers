package com.berlord.bertietiers.logic;

import com.berlord.bertietiers.BertieTiers;
import com.berlord.bertietiers.config.RawConfig;
import com.berlord.bertietiers.config.ValidatedConfig;
import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * A {@link ValidatedConfig} resolved against the live registries and indexed for lookup. Immutable
 * once built; reloading builds a brand-new instance and swaps it in atomically, so a reader never
 * sees a half-updated ruleset.
 */
public final class RuntimeConfig {
    public static final RuntimeConfig EMPTY = new RuntimeConfig(ValidatedConfig.EMPTY);

    private static final int CACHE_LIMIT = 4096;
    private static final int NO_TIER = Integer.MIN_VALUE;

    private final ValidatedConfig source;
    private final Map<Block, Integer> blockLevels = new IdentityHashMap<>();
    private final Map<Item, List<ToolRule>> toolRules = new IdentityHashMap<>();
    private final Map<Item, List<ExceptionRule>> exceptionRules = new IdentityHashMap<>();
    private final Map<String, Integer> levelByTierId = new HashMap<>();

    /** Memoised tool-tier lookups. Component predicates only run on a stack shape we have not seen. */
    private final ConcurrentHashMap<CacheKey, Integer> toolLevelCache = new ConcurrentHashMap<>();
    /** One-shot guard so an ambiguous pair is reported once, not once per swing. */
    private final Set<String> reportedAmbiguities = ConcurrentHashMap.newKeySet();

    public RuntimeConfig(ValidatedConfig source) {
        this.source = source;

        for (ValidatedConfig.Tier tier : source.tiers()) {
            this.levelByTierId.put(tier.id(), tier.level());
            for (String blockId : tier.blocks()) {
                Block block = block(blockId);
                if (block != null) {
                    this.blockLevels.put(block, tier.level());
                }
            }
            for (RawConfig.RawToolMatcher matcher : tier.tools()) {
                Item item = item(matcher.item());
                if (item == null) {
                    continue;
                }
                ToolRule rule = new ToolRule(
                        ComponentPredicate.build(matcher.components(), matcher.path()),
                        tier.level(),
                        tier.id(),
                        matcher.path(),
                        matcher.specificity());
                this.toolRules.computeIfAbsent(item, unused -> new ArrayList<>()).add(rule);
            }
        }

        for (ValidatedConfig.Exception_ rule : source.exceptions()) {
            Item item = item(rule.tool().item());
            if (item == null) {
                continue;
            }
            Set<Block> blocks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (String blockId : rule.blocks()) {
                Block block = block(blockId);
                if (block != null) {
                    blocks.add(block);
                }
            }
            this.exceptionRules
                    .computeIfAbsent(item, unused -> new ArrayList<>())
                    .add(new ExceptionRule(ComponentPredicate.build(rule.tool().components(), rule.tool().path()), blocks));
        }
    }

    public ValidatedConfig source() {
        return this.source;
    }

    public boolean isEmpty() {
        return this.blockLevels.isEmpty();
    }

    public int controlledBlockCount() {
        return this.blockLevels.size();
    }

    public Map<String, Integer> levelByTierId() {
        return Map.copyOf(this.levelByTierId);
    }

    /** Tier level of a controlled block, or null when Bertie does not control it. */
    public Integer blockLevel(Block block) {
        return this.blockLevels.get(block);
    }

    /** True when a point exception names this tool and this block. */
    public boolean exceptionAllows(ItemStack tool, Block block, DynamicOps<JsonElement> ops) {
        List<ExceptionRule> rules = this.exceptionRules.get(tool.getItem());
        if (rules == null) {
            return false;
        }
        for (ExceptionRule rule : rules) {
            if (rule.blocks().contains(block) && rule.predicate().test(tool, ops)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tier level of a tool, or null when it belongs to no tier. When more than one matcher applies
     * the most specific one wins; an exact tie between different levels cannot normally survive
     * config validation, but if it does we take the lowest level (the restrictive answer) and say
     * so in the log once.
     */
    public Integer toolLevel(ItemStack tool, DynamicOps<JsonElement> ops) {
        if (tool.isEmpty()) {
            return null;
        }
        Item item = tool.getItem();
        List<ToolRule> rules = this.toolRules.get(item);
        if (rules == null) {
            return null;
        }
        CacheKey key = new CacheKey(item, tool.getComponentsPatch());
        Integer cached = this.toolLevelCache.get(key);
        if (cached != null) {
            return cached == NO_TIER ? null : cached;
        }
        Integer resolved = resolve(tool, rules, ops);
        if (this.toolLevelCache.size() < CACHE_LIMIT) {
            this.toolLevelCache.put(key, resolved == null ? NO_TIER : resolved);
        }
        return resolved;
    }

    private Integer resolve(ItemStack tool, List<ToolRule> rules, DynamicOps<JsonElement> ops) {
        ToolRule best = null;
        for (ToolRule rule : rules) {
            if (!rule.predicate().test(tool, ops)) {
                continue;
            }
            if (best == null || rule.specificity() > best.specificity()) {
                best = rule;
            } else if (rule.specificity() == best.specificity() && rule.level() != best.level()) {
                reportAmbiguity(best, rule);
                if (rule.level() < best.level()) {
                    best = rule; // tie-break: the restrictive answer, deterministically
                }
            }
        }
        return best == null ? null : best.level();
    }

    private void reportAmbiguity(ToolRule a, ToolRule b) {
        String key = a.path() + " <-> " + b.path();
        if (this.reportedAmbiguities.add(key)) {
            BertieTiers.LOGGER.warn(
                    "Two equally specific tool matchers both match the same stack but sit in different tiers: {} (tier '{}')"
                            + " and {} (tier '{}'). Using the lower level. Give one of them a component predicate that tells"
                            + " them apart.",
                    a.path(), a.tierId(), b.path(), b.tierId());
        }
    }

    private static Block block(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(location);
    }

    private static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(location);
    }

    /** All tool matchers registered for an item, for the diagnostics command. */
    public List<ToolRule> rulesFor(Item item) {
        List<ToolRule> rules = this.toolRules.get(item);
        return rules == null ? List.of() : List.copyOf(rules);
    }

    public record ToolRule(ComponentPredicate predicate, int level, String tierId, String path, int specificity) {}

    private record ExceptionRule(ComponentPredicate predicate, Set<Block> blocks) {}

    /**
     * Cache key. {@link DataComponentPatch} has value equality, so two stacks with the same item
     * and the same component patch always resolve to the same tier.
     */
    private record CacheKey(Item item, DataComponentPatch components) {}

    /** Blocks Bertie controls, grouped for the dump command. */
    public Map<Integer, Set<Block>> blocksByLevel() {
        Map<Integer, Set<Block>> out = new HashMap<>();
        for (Map.Entry<Block, Integer> entry : this.blockLevels.entrySet()) {
            out.computeIfAbsent(entry.getValue(), unused -> new HashSet<>()).add(entry.getKey());
        }
        return out;
    }
}
