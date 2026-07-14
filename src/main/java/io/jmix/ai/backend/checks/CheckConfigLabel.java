package io.jmix.ai.backend.checks;

import org.springframework.lang.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the display label stored with a check run, including legacy-label compatibility. */
final class CheckConfigLabel {

    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final Pattern LEGACY_COHORT_SUFFIX = Pattern.compile(
            " \\[cohort:(?:[a-z0-9]+-)*([a-f0-9]{12})]$");

    private CheckConfigLabel() {
    }

    @Nullable
    static String resolve(@Nullable String storedLabel, @Nullable String parameters) {
        String label = stripLegacySuffix(storedLabel);
        return label == null || label.isBlank() ? extract(parameters) : label;
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

    @Nullable
    static String stripLegacySuffix(@Nullable String label) {
        String result = label;
        while (result != null) {
            Matcher matcher = LEGACY_COHORT_SUFFIX.matcher(result);
            if (!matcher.find()) {
                break;
            }
            result = result.substring(0, matcher.start());
        }
        return result;
    }

    @Nullable
    static String extractLegacyCohortHash(@Nullable String label) {
        if (label == null) {
            return null;
        }
        Matcher matcher = LEGACY_COHORT_SUFFIX.matcher(label);
        return matcher.find() ? matcher.group(1) : null;
    }
}
