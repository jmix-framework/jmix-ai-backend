package io.jmix.ai.backend.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptResolverTest {

    private SystemPromptResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SystemPromptResolver();
    }

    @Test
    void shouldSubstitutePlaceholder() {
        String result = resolver.resolve("specifically for ${jmixVersion}");

        assertThat(result).isEqualTo("specifically for version 2.8");
    }

    @Test
    void shouldReplaceAllOccurrences() {
        String result = resolver.resolve(
                "Jmix ${jmixVersion} or Java. Only assist with Jmix ${jmixVersion}.");

        assertThat(result).isEqualTo("Jmix version 2.8 or Java. Only assist with Jmix version 2.8.");
    }

    @Test
    void shouldReturnTemplateUnchangedWhenNoPlaceholder() {
        String template = "No placeholder here.";

        String result = resolver.resolve(template);

        assertThat(result).isSameAs(template);
    }

    @Test
    void shouldReturnNullWhenTemplateIsNull() {
        String result = resolver.resolve(null);

        assertThat(result).isNull();
    }
}
