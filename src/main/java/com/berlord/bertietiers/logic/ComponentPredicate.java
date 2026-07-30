package com.berlord.bertietiers.logic;

import com.berlord.bertietiers.config.ConfigException;
import com.berlord.bertietiers.config.JsonMatch;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Optional per-stack half of a tool matcher, for items whose single registry ID stands for many
 * actually-different tools. Slag n' Embers is the reason it exists: every assembled Slag tool is
 * one {@code slag:modular_item}, and what makes an iron pickaxe an iron pickaxe lives in its data
 * components ({@code slag:modular_type} for the shape, {@code slag:dynamic_parts} for the parts
 * and their {@code slag:material_type}).
 *
 * <p>Rather than hard-coding Slag, the predicate is generic: each entry names a data-component
 * type, the component's own persistent codec encodes the stack's actual value to JSON, and the
 * configured JSON must be a subset of it ({@link JsonMatch#matches}). Any mod's components work
 * the same way and no Slag class is ever loaded.
 */
public final class ComponentPredicate {
    public static final ComponentPredicate ALWAYS = new ComponentPredicate(List.of());

    private final List<Entry> entries;

    private ComponentPredicate(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Resolves the configured component IDs against the live registry. The config validator has
     * already checked existence, so a failure here means the registry changed underneath us.
     */
    public static ComponentPredicate build(Map<String, JsonElement> spec, String path) {
        if (spec.isEmpty()) {
            return ALWAYS;
        }
        List<Entry> entries = new ArrayList<>(spec.size());
        for (Map.Entry<String, JsonElement> raw : spec.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(raw.getKey());
            if (id == null) {
                throw new ConfigException(path + ".components." + raw.getKey(), "not a valid namespaced ID");
            }
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
            if (type == null) {
                throw new ConfigException(path + ".components." + raw.getKey(), "unknown data-component type");
            }
            Codec<?> codec = type.codec();
            if (codec == null) {
                throw new ConfigException(
                        path + ".components." + raw.getKey(),
                        "data-component type has no persistent codec and can never be matched");
            }
            entries.add(new Entry(type, codec, raw.getValue()));
        }
        return new ComponentPredicate(entries);
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /** True when every configured component is present on the stack and matches. */
    @SuppressWarnings("unchecked")
    public boolean test(ItemStack stack, DynamicOps<JsonElement> ops) {
        for (Entry entry : this.entries) {
            Object value = stack.get(entry.type());
            if (value == null) {
                return false;
            }
            DataResult<JsonElement> encoded = ((Codec<Object>) entry.codec()).encodeStart(ops, value);
            JsonElement actual = encoded.result().orElse(null);
            if (actual == null || !JsonMatch.matches(entry.expected(), actual)) {
                return false;
            }
        }
        return true;
    }

    private record Entry(DataComponentType<?> type, Codec<?> codec, JsonElement expected) {}
}
