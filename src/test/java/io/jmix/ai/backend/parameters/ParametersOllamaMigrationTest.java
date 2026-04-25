package io.jmix.ai.backend.parameters;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParametersOllamaMigrationTest {

    @Test
    void migrateContent_UpdatesLegacyDefaultModelAndDescription() {
        String migrated = ParametersOllamaMigration.migrateContent("""
                description: openapi-compatible, Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf
                model:
                  name: /Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf
                tools:
                  skipForTrivialPrompts: true
                """);

        assertThat(migrated).contains("description: \"classifier_rag | openai-compatible via ollama, qwen3-coder:30b\"");
        assertThat(migrated).contains("name: \"qwen3-coder:30b\"");
        assertThat(migrated).contains("skipForTrivialPrompts: true");
    }

    @Test
    void migrateContent_LeavesCustomModelUntouched() {
        String migrated = ParametersOllamaMigration.migrateContent("""
                description: custom
                model:
                  name: qwen3:4b
                """);

        assertThat(migrated).isNull();
    }

    @Test
    void migrateContent_UpdatesOtherLegacyQwenGgufVariants() {
        String migrated = ParametersOllamaMigration.migrateContent("""
                description: custom imported profile
                model:
                  name: /Qwen3-Coder-30B-A3B-Instruct-Q6_K.gguf
                """);

        assertThat(migrated).contains("name: \"qwen3-coder:30b\"");
        assertThat(migrated).contains("description: \"custom imported profile\"");
    }
}
