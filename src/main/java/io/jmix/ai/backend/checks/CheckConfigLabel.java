package io.jmix.ai.backend.checks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.lang.Nullable;

import java.util.Map;

/** Resolves the display label stored with a check run. */
final class CheckConfigLabel {

    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private CheckConfigLabel() {
    }

    @Nullable
    static String resolveLabel(@Nullable String storedLabel, @Nullable String parameters) {
        return storedLabel == null || storedLabel.isBlank() ? extractDescription(parameters) : storedLabel;
    }

    /**
     * Reads the human-readable label from the top-level {@code description} key of the parameters
     * YAML. The label is decoration, so unlike {@code Parameters.getDescription()} malformed YAML
     * yields no label instead of an exception.
     */
    @Nullable
    static String extractDescription(@Nullable String parameters) {
        if (parameters == null) {
            return null;
        }
        Object description;
        try {
            Map<?, ?> data = YAML_MAPPER.readValue(parameters, Map.class);
            description = data != null ? data.get("description") : null;
        } catch (Exception e) {
            return null;
        }
        if (description == null) {
            return null;
        }
        String value = description.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > MAX_DESCRIPTION_LENGTH
                ? value.substring(0, MAX_DESCRIPTION_LENGTH)
                : value;
    }
}
