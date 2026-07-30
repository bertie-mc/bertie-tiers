package com.berlord.bertietiers.config;

import com.berlord.bertietiers.BertieTiers;
import com.berlord.bertietiers.logic.MiningAuthority;
import com.berlord.bertietiers.logic.RuntimeConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Owns {@code config/bertie_tiers.json}: writes the shipped default on first run, loads it, and
 * swaps the result into {@link MiningAuthority} in one go.
 *
 * <p>Load is all-or-nothing on purpose. Parsing, validation and index building all happen against
 * a fresh object; only once every step succeeded is the new config published. A rejected reload
 * therefore leaves the last working ruleset running untouched, which is what the reload command
 * reports back to the operator.
 */
public final class ConfigManager {
    public static final String FILE_NAME = "bertie_tiers.json";
    private static final String DEFAULT_RESOURCE = "/bertie_tiers/default_config.json";

    private ConfigManager() {}

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /** Result of a load attempt. On failure the previous configuration is still active. */
    public record LoadResult(boolean success, String message, List<String> warnings, RuntimeConfig config) {
        public static LoadResult failure(String message) {
            return new LoadResult(false, message, List.of(), null);
        }
    }

    /**
     * Reads, validates and installs the configuration file. Never throws: the caller (server start
     * or the reload command) gets a report instead, so a typo cannot take a server down.
     */
    public static LoadResult loadAndInstall() {
        Path path = configPath();
        try {
            ensureDefaultExists(path);
        } catch (IOException e) {
            return LoadResult.failure("could not write the default config to " + path + ": " + e);
        }

        JsonElement root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        } catch (IOException e) {
            return LoadResult.failure("could not read " + path + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return LoadResult.failure("malformed JSON in " + path + ": " + e.getMessage());
        }

        ConfigValidator.Result validated;
        try {
            RawConfig raw = ConfigParser.parse(root);
            validated = ConfigValidator.validate(raw, LiveRegistryProbe.INSTANCE);
        } catch (ConfigException e) {
            return LoadResult.failure(e.getMessage());
        } catch (RuntimeException e) {
            return LoadResult.failure("unexpected error while validating " + path + ": " + e);
        }

        RuntimeConfig runtime;
        try {
            runtime = new RuntimeConfig(validated.config());
        } catch (ConfigException e) {
            return LoadResult.failure(e.getMessage());
        } catch (RuntimeException e) {
            return LoadResult.failure("unexpected error while indexing " + path + ": " + e);
        }

        // Every step succeeded - publish the new ruleset as a single atomic swap.
        MiningAuthority.install(runtime);

        List<String> warnings = new ArrayList<>(validated.warnings());
        String message = "loaded " + validated.config().tiers().size() + " tier(s), "
                + runtime.controlledBlockCount() + " controlled block(s), "
                + validated.config().toolMatcherCount() + " tool matcher(s), "
                + validated.config().exceptions().size() + " exception(s)";
        return new LoadResult(true, message, warnings, runtime);
    }

    private static void ensureDefaultExists(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        try (InputStream in = ConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("the shipped default config is missing from the jar");
            }
            Files.copy(in, path);
        }
        BertieTiers.LOGGER.info("Wrote the default mining-tier config to {}", path);
    }
}
