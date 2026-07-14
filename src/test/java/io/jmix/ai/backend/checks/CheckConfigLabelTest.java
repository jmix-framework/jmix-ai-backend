package io.jmix.ai.backend.checks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckConfigLabelTest {

    @Test
    void resolvesStoredLabelWithoutLegacySuffix() {
        String label = "Config"
                + " [cohort:semantic-v3-aaaaaaaaaaaa]"
                + " [cohort:semantic-v3-bbbbbbbbbbbb]";

        assertThat(CheckConfigLabel.resolve(label, "description: Fallback")).isEqualTo("Config");
    }

    @Test
    void fallsBackToDescriptionFromParameters() {
        assertThat(CheckConfigLabel.resolve(" ", "model:\n  name: gpt-5\ndescription: Readable config"))
                .isEqualTo("Readable config");
        assertThat(CheckConfigLabel.resolve(null, "model:\n  name: gpt-5")).isNull();
    }

    @Test
    void limitsDescriptionLength() {
        assertThat(CheckConfigLabel.extract("description: " + "x".repeat(201)))
                .isEqualTo("x".repeat(200));
    }
}
