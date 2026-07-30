package com.berlord.bertietiers.config;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;

/** {@link RegistryProbe} backed by the real registries. */
public final class LiveRegistryProbe implements RegistryProbe {
    public static final LiveRegistryProbe INSTANCE = new LiveRegistryProbe();

    private LiveRegistryProbe() {}

    @Override
    public boolean blockExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.BLOCK.containsKey(location);
    }

    @Override
    public String blockOfItem(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(location);
        if (!(item instanceof BlockItem blockItem)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
    }

    @Override
    public boolean itemExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.ITEM.containsKey(location);
    }

    @Override
    public boolean componentExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(location);
    }

    @Override
    public boolean componentHasPersistentCodec(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return false;
        }
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(location);
        return type != null && type.codec() != null;
    }

    @Override
    public boolean isValidId(String id) {
        return ResourceLocation.tryParse(id) != null;
    }

    @Override
    public boolean namespaceLoaded(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return false;
        }
        String namespace = location.getNamespace();
        if (namespace.equals("minecraft") || namespace.equals("neoforge") || namespace.equals("c")) {
            return true;
        }
        return ModList.get() != null && ModList.get().isLoaded(namespace);
    }
}
