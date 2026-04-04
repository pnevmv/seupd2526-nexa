package it.unipd.dei.se.nexa.utility;


import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

public class ConfigManager {
    private static ConfigManager instance;
    private final Map<String, Object> config;

    private ConfigManager() {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream( "code/java/src/main/resources/params.yml")) {
            config = yaml.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Error reading the params file", e);
        }
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    // Method to get the value as a string
    public String getString(String key) {
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }


    // Method to get the value as an integer
    public Integer getInt(String key) {
        Object value = config.get(key);
        return (value instanceof Number number) ? number.intValue() : null;
    }


    // Method to get the value as a boolean
    public Boolean getBool(String key) {
        Object value = config.get(key);
        return (value instanceof Boolean bool) ? bool : null;
    }

    // Method to get the value as a double
    public Double getDouble(String key) {
        Object value = config.get(key);
        return (value instanceof Number number) ? number.doubleValue() : null;
    }
    
}
