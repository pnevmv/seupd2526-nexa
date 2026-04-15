package it.unipd.dei.se.nexa.utility;

import org.yaml.snakeyaml.Yaml;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConfigManager handles both global and multi-language configuration loading.
 * It uses a thread-safe approach to manage different language instances.
 */
public class ConfigManager {
    // Thread-safe map to store instances for each language
    private static final Map<String, ConfigManager> langInstances = new ConcurrentHashMap<>();

    // Single instance for the global configuration (config.yml)
    private static ConfigManager globalInstance;

    // Holds the configuration data for the specific instance
    private final Map<String, Object> config;

    private static final Path[] CONFIG_DIR_CANDIDATES = {
            Paths.get("src/main/config"),
            Paths.get("code/src/main/config")
    };

    /**
     * Private constructor to load a specific file from the config directory.
     */
    private ConfigManager(String fileName) {
        Yaml yaml = new Yaml();
        File file = resolveConfigFile(fileName).toFile();

        try (InputStream inputStream = new FileInputStream(file)) {
            this.config = yaml.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load configuration file: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * Retrieves the global configuration (src/main/config/config.yml).
     */
    public static synchronized ConfigManager getGlobalConfig() {
        if (globalInstance == null) {
            globalInstance = new ConfigManager("config.yml");
        }
        return globalInstance;
    }

    /**
     * Retrieves the ConfigManager instance for a specific language.
     * Expected file format: src/main/config/config_{lang}.yml
     * * @param lang The language code (e.g., "it", "en", "fr")
     */
    public static ConfigManager getInstance(String lang) {
        return langInstances.computeIfAbsent(lang.toLowerCase(),
                l -> new ConfigManager(String.format("config_%s.yml", l)));
    }

    // --- Data Access Methods ---

    /**
     * Retrieves a String value from the configuration.
     */
    public String getString(String key) {
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Retrieves an Integer value from the configuration.
     */
    public Integer getInt(String key) {
        Object value = config.get(key);
        return (value instanceof Number n) ? n.intValue() : null;
    }

    /**
     * Retrieves a Boolean value from the configuration.
     */
    public Boolean getBool(String key) {
        Object value = config.get(key);
        return (value instanceof Boolean b) ? b : null;
    }

    /**
     * Retrieves a Double value from the configuration.
     */
    public Double getDouble(String key) {
        Object value = config.get(key);
        return (value instanceof Number n) ? n.doubleValue() : null;
    }

    /**
     * Checks if a specific key exists in the configuration.
     */
    public boolean hasKey(String key) {
        return config != null && config.containsKey(key);
    }

    private static Path resolveConfigFile(final String fileName) {
        for (Path configDir : CONFIG_DIR_CANDIDATES) {
            Path candidate = configDir.resolve(fileName);
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }

        return CONFIG_DIR_CANDIDATES[0].resolve(fileName);
    }
}
