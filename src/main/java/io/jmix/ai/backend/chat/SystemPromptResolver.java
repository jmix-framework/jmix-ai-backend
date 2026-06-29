package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.JmixVersion;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SystemPromptResolver {

    public static final String PLACEHOLDER = "${jmixVersion}";

    @Value("${system-prompt.v2}")
    private String systemPromptV2;

    @Value("${system-prompt.v3}")
    private String systemPromptV3;

    /**
     * Substitutes the {@link #PLACEHOLDER} in the active Parameters {@code systemMessage} with the
     * version-specific fragment loaded from properties.
     */
    public String resolve(String systemMessageTemplate, JmixVersion version) {
        if (systemMessageTemplate == null || !systemMessageTemplate.contains(PLACEHOLDER)) {
            return systemMessageTemplate;
        }
        String fragment = version == JmixVersion.V2 ? systemPromptV2 : systemPromptV3;
        if (StringUtils.isEmpty(fragment)) {
            throw new IllegalStateException("System prompt not set for version " + version);
        }
        return systemMessageTemplate.replace(PLACEHOLDER, fragment);
    }
}
