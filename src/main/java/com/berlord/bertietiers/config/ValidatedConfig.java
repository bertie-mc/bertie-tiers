package com.berlord.bertietiers.config;

import java.util.List;

/**
 * A {@link RawConfig} that has passed every check in {@link ConfigValidator}: all IDs exist, block
 * IDs are normalised to block-registry IDs, tier IDs and levels are unique, no block belongs to
 * two tiers and no tool matcher is ambiguous between tiers.
 *
 * <p>Immutable. The live system only ever swaps one of these in wholesale, so a rejected reload
 * can never leave a half-applied state behind.
 */
public record ValidatedConfig(List<Tier> tiers, List<Exception_> exceptions) {
    public static final ValidatedConfig EMPTY = new ValidatedConfig(List.of(), List.of());

    public ValidatedConfig {
        tiers = List.copyOf(tiers);
        exceptions = List.copyOf(exceptions);
    }

    public record Tier(String id, int level, List<RawConfig.RawToolMatcher> tools, List<String> blocks) {
        public Tier {
            tools = List.copyOf(tools);
            blocks = List.copyOf(blocks);
        }
    }

    public record Exception_(RawConfig.RawToolMatcher tool, List<String> blocks) {
        public Exception_ {
            blocks = List.copyOf(blocks);
        }
    }

    public int controlledBlockCount() {
        int total = 0;
        for (Tier tier : this.tiers) {
            total += tier.blocks().size();
        }
        return total;
    }

    public int toolMatcherCount() {
        int total = 0;
        for (Tier tier : this.tiers) {
            total += tier.tools().size();
        }
        return total;
    }
}
