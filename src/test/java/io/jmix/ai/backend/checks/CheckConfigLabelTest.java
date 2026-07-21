package io.jmix.ai.backend.checks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckConfigLabelTest {

    @Test
    void prefersTheStoredLabelOverParameters() {
        assertThat(CheckConfigLabel.resolveLabel("Config", "description: Fallback")).isEqualTo("Config");
    }

    @Test
    void fallsBackToDescriptionFromParameters() {
        assertThat(CheckConfigLabel.resolveLabel(" ", "model:\n  name: gpt-5\ndescription: Readable config"))
                .isEqualTo("Readable config");
        assertThat(CheckConfigLabel.resolveLabel(null, "model:\n  name: gpt-5")).isNull();
    }

    @Test
    void readsOnlyTheTopLevelDescription() {
        assertThat(CheckConfigLabel.extractDescription("model:\n  name: gpt-5\n  description: judge prompt"))
                .isNull();
        assertThat(CheckConfigLabel.extractDescription(
                "model:\n  description: judge prompt\ndescription: Top level"))
                .isEqualTo("Top level");
    }

    @Test
    void unquotesAndFindsDescriptionAnywhereInTheDocument() {
        assertThat(CheckConfigLabel.extractDescription("description: \"Prod config\"")).isEqualTo("Prod config");
        StringBuilder longDocument = new StringBuilder("a:\n");
        for (int i = 0; i < 30; i++) {
            longDocument.append("  b").append(i).append(": 1\n");
        }
        longDocument.append("description: Deep down");
        assertThat(CheckConfigLabel.extractDescription(longDocument.toString())).isEqualTo("Deep down");
    }

    @Test
    void yieldsNoLabelForMalformedOrValuelessYaml() {
        assertThat(CheckConfigLabel.extractDescription("description: [unclosed")).isNull();
        assertThat(CheckConfigLabel.extractDescription("description:")).isNull();
        assertThat(CheckConfigLabel.extractDescription("plain scalar")).isNull();
        assertThat(CheckConfigLabel.extractDescription(null)).isNull();
    }

    @Test
    void limitsDescriptionLength() {
        assertThat(CheckConfigLabel.extractDescription("description: " + "x".repeat(201)))
                .isEqualTo("x".repeat(200));
    }
}
