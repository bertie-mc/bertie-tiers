package com.berlord.bertietiers.config;

/**
 * The only thing {@link ConfigValidator} needs from the game: does this ID exist, and what is it.
 * Keeping it behind an interface is what lets the whole validator be unit tested without booting
 * Minecraft; the live implementation just reads {@code BuiltInRegistries}.
 */
public interface RegistryProbe {
    /** True when {@code id} is a registered block. */
    boolean blockExists(String id);

    /**
     * If {@code id} is a registered item that places a block, the ID of that block; otherwise
     * null. Used so a {@code BlockItem} ID in the config is converted to its block unambiguously.
     */
    String blockOfItem(String id);

    /** True when {@code id} is a registered item. */
    boolean itemExists(String id);

    /** True when {@code id} is a registered data-component type. */
    boolean componentExists(String id);

    /**
     * True when the component type has a persistent (JSON) codec. Transient components can never
     * be matched from a config file, so referencing one is a config error rather than a silent
     * never-match.
     */
    boolean componentHasPersistentCodec(String id);

    /** True when {@code id} parses as a namespaced registry ID. */
    boolean isValidId(String id);

    /**
     * True when the namespace of {@code id} belongs to something that is actually installed.
     *
     * <p>This is what lets one config file cover a pack whose optional mods come and go: entries
     * whose namespace has no loaded mod are skipped with a note instead of failing the whole file,
     * while a typo inside an installed mod's namespace is still a hard error.
     */
    boolean namespaceLoaded(String id);
}
