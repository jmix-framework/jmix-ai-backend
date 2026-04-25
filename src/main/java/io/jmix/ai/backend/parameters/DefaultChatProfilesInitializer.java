package io.jmix.ai.backend.parameters;

import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.core.Resources;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.Authenticated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultChatProfilesInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatProfilesInitializer.class);

    private final UnconstrainedDataManager dataManager;
    private final Resources resources;
    private final ParametersRepository parametersRepository;

    public DefaultChatProfilesInitializer(UnconstrainedDataManager dataManager,
                                          Resources resources,
                                          ParametersRepository parametersRepository) {
        this.dataManager = dataManager;
        this.resources = resources;
        this.parametersRepository = parametersRepository;
    }

    @EventListener
    @Authenticated
    public void initDefaultChatProfiles(ApplicationStartedEvent event) {
        List<Parameters> existingProfiles = dataManager.load(Parameters.class)
                .query("e.targetType = :type")
                .parameter("type", ParametersTargetType.CHAT.getId())
                .list();

        Map<String, Parameters> existingByDescription = new LinkedHashMap<>();
        for (Parameters parameters : existingProfiles) {
            String description = parametersRepository.getReader(parameters).getString("description", "");
            if (!description.isBlank()) {
                existingByDescription.putIfAbsent(description, parameters);
            }
        }

        boolean hasAnyChatProfiles = !existingProfiles.isEmpty();
        int created = 0;
        for (ProfileSeed seed : seeds()) {
            if (existingByDescription.containsKey(seed.description())) {
                continue;
            }
            Parameters parameters = dataManager.create(Parameters.class);
            parameters.setTargetType(ParametersTargetType.CHAT);
            parameters.setActive(!hasAnyChatProfiles && seed.activateByDefault());
            parameters.setContent(seed.content());
            dataManager.save(parameters);
            created++;
        }

        if (created == 0) {
            log.info("Default chat profiles already initialized");
        } else {
            log.info("Initialized {} default chat profile(s)", created);
        }
    }

    private List<ProfileSeed> seeds() {
        return List.of(
                loadSeed("io/jmix/ai/backend/init/default-params-chat.yml", true),
                loadSeed("io/jmix/ai/backend/init/default-params-chat-always-rag.yml", false),
                loadSeed("io/jmix/ai/backend/init/default-params-chat-narrow-rag.yml", false)
        );
    }

    private ProfileSeed loadSeed(String resourcePath, boolean activateByDefault) {
        String content = resources.getResourceAsString(resourcePath);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Failed to load chat profile seed: " + resourcePath);
        }
        String description = parametersRepository.getReader(content).getString("description", resourcePath);
        return new ProfileSeed(resourcePath, description, content, activateByDefault);
    }

    private record ProfileSeed(
            String resourcePath,
            String description,
            String content,
            boolean activateByDefault
    ) {
    }
}
