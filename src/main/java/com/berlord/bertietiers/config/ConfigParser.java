package com.berlord.bertietiers.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the raw JSON document into a {@link RawConfig}. Structure only - nothing is checked
 * against a registry here, that is {@link ConfigValidator}'s job. Every failure names the exact
 * JSON path, so this stage is pure and unit tested without Minecraft.
 */
public final class ConfigParser {
    private ConfigParser() {}

    public static RawConfig parse(JsonElement root) {
        JsonObject obj = expectObject(root, "<root>");
        List<RawConfig.RawTier> tiers = new ArrayList<>();
        JsonArray tierArray = optionalArray(obj, "tiers", "tiers");
        for (int i = 0; i < tierArray.size(); i++) {
            tiers.add(parseTier(tierArray.get(i), "tiers[" + i + "]"));
        }

        List<RawConfig.RawException> exceptions = new ArrayList<>();
        JsonArray exceptionArray = optionalArray(obj, "exceptions", "exceptions");
        for (int i = 0; i < exceptionArray.size(); i++) {
            exceptions.add(parseException(exceptionArray.get(i), "exceptions[" + i + "]"));
        }

        rejectUnknownKeys(obj, "<root>", "tiers", "exceptions", "_comment");
        return new RawConfig(tiers, exceptions);
    }

    private static RawConfig.RawTier parseTier(JsonElement element, String path) {
        JsonObject obj = expectObject(element, path);
        String id = requireString(obj, "id", path + ".id");
        int level = requireInt(obj, "level", path + ".level");

        List<RawConfig.RawToolMatcher> tools = new ArrayList<>();
        JsonArray toolArray = optionalArray(obj, "tools", path + ".tools");
        for (int i = 0; i < toolArray.size(); i++) {
            tools.add(parseToolMatcher(toolArray.get(i), path + ".tools[" + i + "]"));
        }

        List<RawConfig.RawBlockRef> blocks = new ArrayList<>();
        JsonArray blockArray = optionalArray(obj, "blocks", path + ".blocks");
        for (int i = 0; i < blockArray.size(); i++) {
            String blockPath = path + ".blocks[" + i + "]";
            blocks.add(new RawConfig.RawBlockRef(expectString(blockArray.get(i), blockPath), blockPath));
        }

        rejectUnknownKeys(obj, path, "id", "level", "tools", "blocks", "_comment");
        return new RawConfig.RawTier(id, level, tools, blocks, path);
    }

    private static RawConfig.RawException parseException(JsonElement element, String path) {
        JsonObject obj = expectObject(element, path);
        if (!obj.has("tool")) {
            throw new ConfigException(path + ".tool", "missing required field");
        }
        RawConfig.RawToolMatcher tool = parseToolMatcher(obj.get("tool"), path + ".tool");

        List<RawConfig.RawBlockRef> canMine = new ArrayList<>();
        JsonArray blockArray = optionalArray(obj, "can_mine", path + ".can_mine");
        if (blockArray.isEmpty()) {
            throw new ConfigException(path + ".can_mine", "must list at least one block");
        }
        for (int i = 0; i < blockArray.size(); i++) {
            String blockPath = path + ".can_mine[" + i + "]";
            canMine.add(new RawConfig.RawBlockRef(expectString(blockArray.get(i), blockPath), blockPath));
        }

        rejectUnknownKeys(obj, path, "tool", "can_mine", "_comment");
        return new RawConfig.RawException(tool, canMine, path);
    }

    private static RawConfig.RawToolMatcher parseToolMatcher(JsonElement element, String path) {
        // A bare string is accepted as shorthand for {"item": "..."} with no component predicate.
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new RawConfig.RawToolMatcher(element.getAsString(), Map.of(), path);
        }
        JsonObject obj = expectObject(element, path);
        String item = requireString(obj, "item", path + ".item");

        Map<String, JsonElement> components = new LinkedHashMap<>();
        if (obj.has("components") && !obj.get("components").isJsonNull()) {
            JsonElement raw = obj.get("components");
            if (!raw.isJsonObject()) {
                throw new ConfigException(path + ".components", "expected a JSON object keyed by data-component ID");
            }
            for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
                if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                    throw new ConfigException(path + ".components." + entry.getKey(), "value must not be null");
                }
                components.put(entry.getKey(), entry.getValue());
            }
            if (components.isEmpty()) {
                throw new ConfigException(path + ".components", "must not be empty - omit the field instead");
            }
        }

        rejectUnknownKeys(obj, path, "item", "components", "_comment");
        return new RawConfig.RawToolMatcher(item, components, path);
    }

    private static void rejectUnknownKeys(JsonObject obj, String path, String... allowed) {
        for (String key : obj.keySet()) {
            boolean known = false;
            for (String candidate : allowed) {
                if (candidate.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new ConfigException(path + "." + key, "unknown field (allowed: " + String.join(", ", allowed) + ")");
            }
        }
    }

    private static JsonObject expectObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw new ConfigException(path, "expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static String expectString(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ConfigException(path, "expected a string");
        }
        return element.getAsString();
    }

    private static JsonArray optionalArray(JsonObject obj, String key, String path) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return new JsonArray();
        }
        if (!obj.get(key).isJsonArray()) {
            throw new ConfigException(path, "expected a JSON array");
        }
        return obj.getAsJsonArray(key);
    }

    private static String requireString(JsonObject obj, String key, String path) {
        if (!obj.has(key)) {
            throw new ConfigException(path, "missing required field");
        }
        return expectString(obj.get(key), path);
    }

    private static int requireInt(JsonObject obj, String key, String path) {
        if (!obj.has(key)) {
            throw new ConfigException(path, "missing required field");
        }
        JsonElement element = obj.get(key);
        if (!element.isJsonPrimitive()) {
            throw new ConfigException(path, "expected an integer");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new ConfigException(path, "expected an integer");
        }
        double value = primitive.getAsDouble();
        if (value != Math.floor(value) || Double.isInfinite(value)) {
            throw new ConfigException(path, "expected an integer, got " + primitive.getAsString());
        }
        return (int) value;
    }
}
