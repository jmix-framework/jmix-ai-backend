package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.JmixVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemPromptResolverTest {

    private SystemPromptResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SystemPromptResolver();
        ReflectionTestUtils.setField(resolver, "systemPromptV2", "version 2.8");
        ReflectionTestUtils.setField(resolver, "systemPromptV3", "version 3.0");
    }

    @Test
    void shouldSubstituteV2Placeholder() {
        String result = resolver.resolve("specifically for ${jmixVersion}", JmixVersion.V2);

        assertThat(result).isEqualTo("specifically for version 2.8");
    }

    @Test
    void shouldSubstituteV3Placeholder() {
        String result = resolver.resolve("specifically for ${jmixVersion}", JmixVersion.V3);

        assertThat(result).isEqualTo("specifically for version 3.0");
    }

    @Test
    void shouldReplaceAllOccurrences() {
        String result = resolver.resolve(
                "Jmix ${jmixVersion} or Java. Only assist with Jmix ${jmixVersion}.", JmixVersion.V2);

        assertThat(result).isEqualTo("Jmix version 2.8 or Java. Only assist with Jmix version 2.8.");
    }

    @Test
    void shouldReturnTemplateUnchangedWhenNoPlaceholder() {
        String template = "No placeholder here.";

        String result = resolver.resolve(template, JmixVersion.V2);

        assertThat(result).isSameAs(template);
    }

    @Test
    void shouldReturnNullWhenTemplateIsNull() {
        String result = resolver.resolve(null, JmixVersion.V2);

        assertThat(result).isNull();
    }

    @Test
    void shouldThrowWhenPropertyIsEmpty() {
        ReflectionTestUtils.setField(resolver, "systemPromptV3", "");

        assertThrows(IllegalStateException.class,
                () -> resolver.resolve("${jmixVersion}", JmixVersion.V3));
    }
}
