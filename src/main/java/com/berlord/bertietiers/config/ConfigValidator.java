package com.berlord.bertietiers.config;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every check the prompt asks for, run against a {@link RegistryProbe} rather than the live
 * registries so the whole thing is unit testable:
 *
 * <ul>
 *   <li>tier IDs and numeric levels are unique;</li>
 *   <li>one tool matcher cannot ambiguously belong to two tiers;</li>
 *   <li>one controlled block cannot belong to two tiers;</li>
 *   <li>every registry ID exists (items, blocks, data-component types);</li>
 *   <li>a tool's item ID really is an item, so it can be matched against a stack;</li>
 *   <li>component predicates name components that have a persistent codec to decode against;</li>
 *   <li>exceptions reference existing tools and blocks.</li>
 * </ul>
 *
 * Anything rejected throws {@link ConfigException} with the exact JSON path, and the caller keeps
 * the previous configuration - a bad file never lands half-applied.
 */
public final class ConfigValidator {
    private ConfigValidator() {}

    /** A successfully validated configuration plus non-fatal notes worth logging. */
    public record Result(ValidatedConfig config, List<String> warnings) {}

    public static Result validate(RawConfig raw, RegistryProbe probe) {
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> skippedByNamespace = new LinkedHashMap<>();

        Set<String> seenTierIds = new HashSet<>();
        Map<Integer, String> seenLevels = new HashMap<>();
        Map<String, String> blockOwner = new HashMap<>();
        List<MatcherRef> allToolMatchers = new ArrayList<>();
        List<ValidatedConfig.Tier> tiers = new ArrayList<>();

        for (RawConfig.RawTier tier : raw.tiers()) {
            if (tier.id().isBlank()) {
                throw new ConfigException(tier.path() + ".id", "tier id must not be blank");
            }
            if (!seenTierIds.add(tier.id())) {
                throw new ConfigException(tier.path() + ".id", "duplicate tier id '" + tier.id() + "'");
            }
            String previousOwner = seenLevels.put(tier.level(), tier.id());
            if (previousOwner != null) {
                throw new ConfigException(
                        tier.path() + ".level",
                        "level " + tier.level() + " is already used by tier '" + previousOwner + "' - levels must be unique");
            }

            List<RawConfig.RawToolMatcher> tools = new ArrayList<>();
            for (RawConfig.RawToolMatcher matcher : tier.tools()) {
                if (skipMissingMod(matcher.item(), matcher.path() + ".item", probe, skippedByNamespace)) {
                    continue;
                }
                validateToolMatcher(matcher, probe);
                tools.add(matcher);
                allToolMatchers.add(new MatcherRef(matcher, tier.id(), tier.level()));
            }

            Set<String> blocks = new LinkedHashSet<>();
            for (RawConfig.RawBlockRef ref : tier.blocks()) {
                if (skipMissingMod(ref.id(), ref.path(), probe, skippedByNamespace)) {
                    continue;
                }
                String blockId = resolveBlock(ref, probe);
                if (!blocks.add(blockId)) {
                    throw new ConfigException(ref.path(), "block '" + blockId + "' is listed twice in tier '" + tier.id() + "'");
                }
                String owner = blockOwner.put(blockId, tier.id());
                if (owner != null) {
                    throw new ConfigException(
                            ref.path(),
                            "block '" + blockId + "' already belongs to tier '" + owner + "' - a block may belong to exactly one tier");
                }
            }

            tiers.add(new ValidatedConfig.Tier(tier.id(), tier.level(), tools, List.copyOf(blocks)));
        }

        checkToolAmbiguity(allToolMatchers);

        List<ValidatedConfig.Exception_> exceptions = new ArrayList<>();
        for (RawConfig.RawException rule : raw.exceptions()) {
            if (skipMissingMod(rule.tool().item(), rule.tool().path() + ".item", probe, skippedByNamespace)) {
                continue;
            }
            validateToolMatcher(rule.tool(), probe);
            Set<String> blocks = new LinkedHashSet<>();
            for (RawConfig.RawBlockRef ref : rule.canMine()) {
                if (skipMissingMod(ref.id(), ref.path(), probe, skippedByNamespace)) {
                    continue;
                }
                String blockId = resolveBlock(ref, probe);
                blocks.add(blockId);
                if (!blockOwner.containsKey(blockId)) {
                    warnings.add(ref.path() + ": block '" + blockId
                            + "' is not listed in any tier, so this exception can never do anything"
                            + " (unlisted blocks keep their original behaviour)");
                }
            }
            if (!blocks.isEmpty()) {
                exceptions.add(new ValidatedConfig.Exception_(rule.tool(), List.copyOf(blocks)));
            }
        }

        for (Map.Entry<String, Integer> entry : skippedByNamespace.entrySet()) {
            warnings.add("skipped " + entry.getValue() + " entr" + (entry.getValue() == 1 ? "y" : "ies")
                    + " because no mod with namespace '" + entry.getKey() + "' is installed");
        }

        return new Result(new ValidatedConfig(tiers, exceptions), List.copyOf(warnings));
    }

    /**
     * Entries belonging to a mod that is not installed are dropped with one summary note rather
     * than failing the file, so one config can be shipped for a pack whose optional mods come and
     * go.
     */
    private static boolean skipMissingMod(String id, String path, RegistryProbe probe, Map<String, Integer> skipped) {
        if (!probe.isValidId(id)) {
            throw new ConfigException(path, "'" + id + "' is not a valid namespaced ID");
        }
        if (probe.namespaceLoaded(id)) {
            return false;
        }
        skipped.merge(namespaceOf(id), 1, Integer::sum);
        return true;
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static void validateToolMatcher(RawConfig.RawToolMatcher matcher, RegistryProbe probe) {
        String itemPath = matcher.path() + ".item";
        if (!probe.isValidId(matcher.item())) {
            throw new ConfigException(itemPath, "'" + matcher.item() + "' is not a valid namespaced ID");
        }
        if (!probe.itemExists(matcher.item())) {
            throw new ConfigException(itemPath, "unknown item '" + matcher.item() + "'");
        }
        for (Map.Entry<String, JsonElement> entry : matcher.components().entrySet()) {
            String path = matcher.path() + ".components." + entry.getKey();
            if (!probe.isValidId(entry.getKey())) {
                throw new ConfigException(path, "'" + entry.getKey() + "' is not a valid namespaced ID");
            }
            if (!probe.componentExists(entry.getKey())) {
                throw new ConfigException(path, "unknown data-component type '" + entry.getKey() + "'");
            }
            if (!probe.componentHasPersistentCodec(entry.getKey())) {
                throw new ConfigException(
                        path,
                        "data-component type '" + entry.getKey()
                                + "' has no persistent codec, so its value can never be matched from a config file");
            }
        }
    }

    private static String resolveBlock(RawConfig.RawBlockRef ref, RegistryProbe probe) {
        if (!probe.isValidId(ref.id())) {
            throw new ConfigException(ref.path(), "'" + ref.id() + "' is not a valid namespaced ID");
        }
        if (probe.blockExists(ref.id())) {
            return ref.id();
        }
        String viaItem = probe.blockOfItem(ref.id());
        if (viaItem != null) {
            return viaItem;
        }
        if (probe.itemExists(ref.id())) {
            throw new ConfigException(
                    ref.path(),
                    "'" + ref.id() + "' is an item that does not place a block - list the block ID instead");
        }
        throw new ConfigException(ref.path(), "unknown block '" + ref.id() + "'");
    }

    /**
     * Two matchers for the same item are only allowed to live in different tiers when the outcome
     * for any given stack is unambiguous: either they can provably never both match, or one is
     * strictly more specific and therefore always wins.
     */
    private static void checkToolAmbiguity(List<MatcherRef> matchers) {
        for (int i = 0; i < matchers.size(); i++) {
            for (int j = i + 1; j < matchers.size(); j++) {
                MatcherRef a = matchers.get(i);
                MatcherRef b = matchers.get(j);
                if (!a.matcher().item().equals(b.matcher().item())) {
                    continue;
                }
                boolean identical = a.matcher().components().equals(b.matcher().components());
                if (identical) {
                    if (a.tierId().equals(b.tierId())) {
                        throw new ConfigException(
                                b.matcher().path(),
                                "duplicate tool matcher for '" + b.matcher().item() + "' inside tier '" + b.tierId() + "'"
                                        + " (first seen at " + a.matcher().path() + ")");
                    }
                    throw new ConfigException(
                            b.matcher().path(),
                            "tool matcher for '" + b.matcher().item() + "' appears in tier '" + a.tierId()
                                    + "' (at " + a.matcher().path() + ") and tier '" + b.tierId()
                                    + "' - a tool may belong to exactly one tier");
                }
                if (a.tierId().equals(b.tierId())) {
                    continue; // same tier, same level - overlap changes nothing
                }
                if (provablyDisjoint(a.matcher(), b.matcher())) {
                    continue;
                }
                if (a.matcher().specificity() != b.matcher().specificity()) {
                    continue; // the more specific matcher deterministically wins
                }
                throw new ConfigException(
                        b.matcher().path(),
                        "tool matcher for '" + b.matcher().item() + "' in tier '" + b.tierId()
                                + "' is equally specific to the one in tier '" + a.tierId() + "' (at "
                                + a.matcher().path() + ") and they may match the same stack - add a component"
                                + " predicate that tells them apart, or move them into the same tier");
            }
        }
    }

    private static boolean provablyDisjoint(RawConfig.RawToolMatcher a, RawConfig.RawToolMatcher b) {
        for (Map.Entry<String, JsonElement> entry : a.components().entrySet()) {
            JsonElement other = b.components().get(entry.getKey());
            if (other != null && JsonMatch.provablyDisjoint(entry.getValue(), other)) {
                return true;
            }
        }
        return false;
    }

    private record MatcherRef(RawConfig.RawToolMatcher matcher, String tierId, int level) {}
}
