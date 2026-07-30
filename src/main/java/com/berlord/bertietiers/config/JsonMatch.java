package com.berlord.bertietiers.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/**
 * Pure JSON tree helpers used by the tool component predicate. Deliberately free of any
 * Minecraft type so the whole matching rule can be unit tested off-game.
 *
 * <p>A component predicate is written as the JSON the component <em>would</em> encode to, but
 * only the parts you care about. {@link #matches} implements that "configured JSON is a subset
 * of the actual JSON" rule:
 *
 * <ul>
 *   <li>primitive (and null): must be equal;</li>
 *   <li>object: every key present in the expected object must exist in the actual object and
 *       match recursively - extra keys in the actual object are ignored;</li>
 *   <li>array: every expected element must match <em>some</em> actual element (order and extra
 *       elements are ignored).</li>
 * </ul>
 *
 * That single rule is what lets one Slag {@code slag:modular_item} be told apart from another:
 * {@code slag:modular_type} pins the tool shape and a one-element {@code slag:dynamic_parts}
 * array pins the material of a specific part.
 */
public final class JsonMatch {
    private JsonMatch() {}

    /** Returns true when {@code expected} is a subset of {@code actual} under the rule above. */
    public static boolean matches(JsonElement expected, JsonElement actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) {
                return false;
            }
            JsonObject actualObj = actual.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : expected.getAsJsonObject().entrySet()) {
                if (!actualObj.has(entry.getKey())) {
                    return false;
                }
                if (!matches(entry.getValue(), actualObj.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray()) {
                return false;
            }
            JsonArray actualArr = actual.getAsJsonArray();
            outer:
            for (JsonElement wanted : expected.getAsJsonArray()) {
                for (JsonElement candidate : actualArr) {
                    if (matches(wanted, candidate)) {
                        continue outer;
                    }
                }
                return false;
            }
            return true;
        }
        return expected.equals(actual);
    }

    /**
     * Conservative "these two predicates can never match the same stack" test, used by the config
     * validator to allow sibling matchers that differ only in one scalar (iron head vs diamond
     * head) without reporting them as an ambiguous overlap.
     *
     * <p>Sound for primitives and objects. The single-element array case is a documented
     * heuristic: {@code [X]} and {@code [Y]} are treated as disjoint when {@code X} and {@code Y}
     * are, which is what "one pickaxe head per tool" means in practice but is not provable from
     * the JSON alone. If the heuristic is ever wrong the runtime tie-break in
     * {@link com.berlord.bertietiers.logic.ToolTierIndex} still keeps the outcome deterministic
     * and logs a diagnostic.
     */
    public static boolean provablyDisjoint(JsonElement a, JsonElement b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            return !a.equals(b);
        }
        if (a.isJsonObject() && b.isJsonObject()) {
            JsonObject ao = a.getAsJsonObject();
            JsonObject bo = b.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : ao.entrySet()) {
                if (bo.has(entry.getKey()) && provablyDisjoint(entry.getValue(), bo.get(entry.getKey()))) {
                    return true;
                }
            }
            return false;
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            JsonArray aa = a.getAsJsonArray();
            JsonArray ba = b.getAsJsonArray();
            return aa.size() == 1 && ba.size() == 1 && provablyDisjoint(aa.get(0), ba.get(0));
        }
        return false;
    }

    /**
     * Number of leaf values in the expected tree. Used as the specificity score: when several
     * matchers for the same item match a stack, the one that pinned down more values wins.
     */
    public static int specificity(JsonElement element) {
        if (element == null) {
            return 0;
        }
        if (element.isJsonObject()) {
            int total = 0;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                total += specificity(entry.getValue());
            }
            return total;
        }
        if (element.isJsonArray()) {
            int total = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                total += specificity(child);
            }
            return total;
        }
        return 1;
    }
}
