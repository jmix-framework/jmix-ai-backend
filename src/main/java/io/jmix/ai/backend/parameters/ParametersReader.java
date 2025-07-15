package io.jmix.ai.backend.parameters;

import java.util.List;
import java.util.Map;

public class ParametersReader {

    private final Map<String, Object> parametersMap;

    public ParametersReader(Map<String, Object> parametersMap) {
        this.parametersMap = parametersMap;
    }

    public String getString(String key) {
        return getString(key, "");
    }

    public String getString(String key, String defaultValue) {
        Object value = getValue(key);
        return value != null ? value.toString() : defaultValue;
    }

    public int getInt(String key) {
        return getInteger(key, 0);
    }

    public Integer getInteger(String key, Integer defaultValue) {
        Object value = getValue(key);
        return value != null ? (Integer) value : defaultValue;
    }

    public double getDouble(String key) {
        return getDouble(key, 0.0);
    }

    public Double getDouble(String key, Double defaultValue) {
        Object value = getValue(key);
        return value != null ? (Double) value : defaultValue;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public Boolean getBoolean(String key, Boolean defaultValue) {
        Object value = getValue(key);
        return value != null ? (Boolean) value : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getList(String key) {
        Object value = getValue(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    /**
     * Retrieves a value from a nested Map using dot notation.
     * @param key The dot notation key (e.g., "address.street")
     * @return The value, or null if the key path is invalid
     */
    public Object getValue(String key) {
        if (parametersMap == null || key == null || key.isEmpty()) {
            return null;
        }

        // Split the key into parts
        String[] parts = key.split("\\.");
        Map<String, Object> current = parametersMap;
        Object value = null;

        // Traverse the parametersMap for each part of the key
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];

            // If this is the last part, get the value
            if (i == parts.length - 1) {
                value = current.get(part);
            } else {
                // Get the next nested map
                Object next = current.get(part);
                if (next instanceof Map) {
                    current = (Map<String, Object>) next;
                } else {
                    return null; // Path is invalid
                }
            }
        }

        return value;
    }
}
