package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.JmixVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckFingerprintsTest {

    @Test
    void definitionFingerprintIsStableAndCompatible() {
        CheckDef first = checkDef(
                "10000000-0000-0000-0000-000000000001", "data", "Question 1", "Answer 1");
        CheckDef second = checkDef(
                "10000000-0000-0000-0000-000000000002", "ui", "Question 2", "Answer 2");

        String fingerprint = CheckFingerprints.forDefinitions(List.of(first, second));

        assertThat(fingerprint).isEqualTo("definitions-fingerprint-version-2026-07-26-6a6cbbf13d01");
        assertThat(CheckFingerprints.forDefinitions(List.of(second, first))).isEqualTo(fingerprint);

        second.setAnswer("Changed answer");
        assertThat(CheckFingerprints.forDefinitions(List.of(first, second))).isNotEqualTo(fingerprint);
    }

    @Test
    void definitionFingerprintDoesNotIncludeVersionScope() {
        CheckDef definition = checkDef(
                "10000000-0000-0000-0000-000000000001", "data", "Question", "Answer");
        definition.setJmixVersion(JmixVersion.V2);
        String v2 = CheckFingerprints.forDefinitions(List.of(definition));

        definition.setJmixVersion(JmixVersion.V3);

        assertThat(CheckFingerprints.forDefinitions(List.of(definition))).isEqualTo(v2);
    }

    @Test
    void shortHashRemainsCompatible() {
        assertThat(CheckFingerprints.shortHash("abc")).isEqualTo("ba7816bf8f01");
    }

    private static CheckDef checkDef(String id, String category, String question, String answer) {
        CheckDef checkDef = new CheckDef();
        checkDef.setId(UUID.fromString(id));
        checkDef.setCategory(category);
        checkDef.setQuestion(question);
        checkDef.setAnswer(answer);
        return checkDef;
    }
}
