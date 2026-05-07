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

    // Project root derived from whichever config candidate matched
    private static Path projectRoot;

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
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return null;
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

    /**
     * Resolves a path from config relative to the project root, regardless of CWD.
     * If the path is already absolute, returns it as-is.
     */
    public static Path resolvePath(String relativePath) {
        if (relativePath == null) return null;
        Path p = Paths.get(relativePath);
        if (p.isAbsolute()) return p;
        if (projectRoot == null) getGlobalConfig();
        return projectRoot.resolve(p).normalize();
    }

    private static Path resolveConfigFile(final String fileName) {
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < CONFIG_DIR_CANDIDATES.length; i++) {
            Path candidate = CONFIG_DIR_CANDIDATES[i].resolve(fileName);
            if (Files.isReadable(candidate)) {
                // candidate 0 = "src/main/config" means CWD is code/, root is one level up
                // candidate 1 = "code/src/main/config" means CWD is the repo root
                projectRoot = (i == 0) ? cwd.getParent() : cwd;
                return candidate;
            }
        }
        projectRoot = cwd;
        return CONFIG_DIR_CANDIDATES[0].resolve(fileName);
    }
}
