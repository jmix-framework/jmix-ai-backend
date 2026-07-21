package io.jmix.ai.backend.checks;

import org.springframework.lang.Nullable;

/** Resolves the display label stored with a check run. */
final class CheckConfigLabel {

    private static final int MAX_DESCRIPTION_LENGTH = 200;

    private CheckConfigLabel() {
    }

    @Nullable
    static String resolve(@Nullable String storedLabel, @Nullable String parameters) {
        return storedLabel == null || storedLabel.isBlank() ? extract(parameters) : storedLabel;
    }

    /**
     * Reads the human-readable label from the {@code description:} line of the parameters YAML.
     */
    @Nullable
    static String extract(@Nullable String parameters) {
        if (parameters == null) {
            return null;
        }
        for (String line : parameters.split("\n", 20)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("description:")) {
                String value = trimmed.substring("description:".length()).trim();
                return value.length() > MAX_DESCRIPTION_LENGTH
                        ? value.substring(0, MAX_DESCRIPTION_LENGTH)
                        : value;
            }
        }
        return null;
    }
}
