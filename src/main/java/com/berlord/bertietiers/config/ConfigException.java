package com.berlord.bertietiers.config;

/**
 * Thrown for every rejected configuration. The message always starts with the exact JSON path of
 * the offending field, e.g. {@code tiers[2].tools[0].item: unknown item 'minecraft:stone_pick'}.
 */
public class ConfigException extends RuntimeException {
    private final String path;

    public ConfigException(String path, String message) {
        super(path + ": " + message);
        this.path = path;
    }

    public String path() {
        return this.path;
    }
}
