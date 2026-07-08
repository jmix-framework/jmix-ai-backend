package io.jmix.ai.backend.chat;

import org.springframework.stereotype.Component;

@Component
public class SystemPromptResolver {

    public static final String PLACEHOLDER = "${jmixVersion}";

    private static final String JMIX_VERSION = "version 2.8";

    /**
     * Substitutes the {@link #PLACEHOLDER} in the active Parameters {@code systemMessage}.
     */
    public String resolve(String systemMessageTemplate) {
        if (systemMessageTemplate == null || !systemMessageTemplate.contains(PLACEHOLDER)) {
            return systemMessageTemplate;
        }
        return systemMessageTemplate.replace(PLACEHOLDER, JMIX_VERSION);
    }
}
