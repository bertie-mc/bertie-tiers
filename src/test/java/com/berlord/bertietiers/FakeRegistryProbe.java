package com.berlord.bertietiers;

import com.berlord.bertietiers.config.RegistryProbe;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory {@link RegistryProbe} so the validator can be tested without booting Minecraft. */
public final class FakeRegistryProbe implements RegistryProbe {
    private final Set<String> blocks = new HashSet<>();
    private final Set<String> items = new HashSet<>();
    private final Map<String, String> blockItems = new HashMap<>();
    private final Set<String> components = new HashSet<>();
    private final Set<String> transientComponents = new HashSet<>();
    private final Set<String> loadedNamespaces = new HashSet<>(Set.of("minecraft", "neoforge", "c"));

    /** Marks a mod namespace as installed; anything else is treated as "mod not present". */
    public FakeRegistryProbe mod(String namespace) {
        this.loadedNamespaces.add(namespace);
        return this;
    }

    public FakeRegistryProbe block(String id) {
        this.blocks.add(id);
        this.items.add(id);
        this.blockItems.put(id, id);
        return this;
    }

    /** A block whose item has a different ID, e.g. redstone_ore vs redstone. */
    public FakeRegistryProbe blockWithItem(String blockId, String itemId) {
        this.blocks.add(blockId);
        this.items.add(itemId);
        this.blockItems.put(itemId, blockId);
        return this;
    }

    public FakeRegistryProbe item(String id) {
        this.items.add(id);
        return this;
    }

    public FakeRegistryProbe component(String id) {
        this.components.add(id);
        return this;
    }

    public FakeRegistryProbe transientComponent(String id) {
        this.components.add(id);
        this.transientComponents.add(id);
        return this;
    }

    @Override
    public boolean blockExists(String id) {
        return this.blocks.contains(id);
    }

    @Override
    public String blockOfItem(String id) {
        return this.blockItems.get(id);
    }

    @Override
    public boolean itemExists(String id) {
        return this.items.contains(id);
    }

    @Override
    public boolean componentExists(String id) {
        return this.components.contains(id);
    }

    @Override
    public boolean componentHasPersistentCodec(String id) {
        return this.components.contains(id) && !this.transientComponents.contains(id);
    }

    @Override
    public boolean isValidId(String id) {
        return id.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    }

    @Override
    public boolean namespaceLoaded(String id) {
        int colon = id.indexOf(':');
        return this.loadedNamespaces.contains(colon < 0 ? "minecraft" : id.substring(0, colon));
    }
}
