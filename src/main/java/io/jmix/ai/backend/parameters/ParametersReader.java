package io.jmix.ai.backend.parameters;

import java.util.Map;

public class ParametersReader {

    private final Map<String, Object> parametersMap;

    public ParametersReader(Map<String, Object> parametersMap) {
        this.parametersMap = parametersMap;
    }

    public String getString(String key) {
        Object value = getValue(key);
        return value != null ? value.toString() : null;
    }

    public Integer getInteger(String key) {
        Object value = getValue(key);
        return value != null ? (Integer) value : 0;
    }

    public Double getDouble(String key) {
        Object value = getValue(key);
        return value != null ? (Double) value : 0.0;
    }

    public Boolean getBoolean(String key) {
        Object value = getValue(key);
        return value != null ? (Boolean) value : false;
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
