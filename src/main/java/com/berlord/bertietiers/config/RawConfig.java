package com.berlord.bertietiers.config;

import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The configuration exactly as written in the file: still plain strings and JSON, not yet checked
 * against any registry. Every record carries the JSON path it came from so validation errors can
 * point at the precise field.
 */
public final class RawConfig {
    private final List<RawTier> tiers;
    private final List<RawException> exceptions;

    public RawConfig(List<RawTier> tiers, List<RawException> exceptions) {
        this.tiers = List.copyOf(tiers);
        this.exceptions = List.copyOf(exceptions);
    }

    public List<RawTier> tiers() {
        return this.tiers;
    }

    public List<RawException> exceptions() {
        return this.exceptions;
    }

    /** One tier: a name, a numeric level and the tools and blocks that sit at that level. */
    public record RawTier(String id, int level, List<RawToolMatcher> tools, List<RawBlockRef> blocks, String path) {}

    /** A block entry; {@code id} may be a block ID or the ID of its {@code BlockItem}. */
    public record RawBlockRef(String id, String path) {}

    /**
     * A tool entry: an item ID plus an optional data-component predicate for items whose single
     * registry ID covers many actually-different tools (Slag's {@code slag:modular_item}).
     */
    public record RawToolMatcher(String item, Map<String, JsonElement> components, String path) {
        public RawToolMatcher {
            components = Map.copyOf(new LinkedHashMap<>(components));
        }

        /** Higher = pinned down more values; used to pick a winner when several matchers apply. */
        public int specificity() {
            int total = 0;
            for (JsonElement value : this.components.values()) {
                total += JsonMatch.specificity(value);
            }
            return total;
        }
    }

    /** A point exception: this exact tool may mine these exact blocks, whatever the tiers say. */
    public record RawException(RawToolMatcher tool, List<RawBlockRef> canMine, String path) {}
}
