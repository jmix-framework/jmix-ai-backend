package io.jmix.ai.backend.parameters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.Authenticated;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ParametersOllamaMigration {

    static final String TARGET_MODEL_NAME = "qwen3-coder:30b";
    static final String LEGACY_DEFAULT_DESCRIPTION = "openapi-compatible, Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf";
    static final String TARGET_DEFAULT_DESCRIPTION = "classifier_rag | openai-compatible via ollama, qwen3-coder:30b";
    private static final Logger log = LoggerFactory.getLogger(ParametersOllamaMigration.class);
    private static final Set<String> LEGACY_MODEL_NAMES = Set.of(
            "/Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf",
            "Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf"
    );

    private final UnconstrainedDataManager dataManager;
    private final ObjectMapper objectMapper;

    public ParametersOllamaMigration(UnconstrainedDataManager dataManager) {
        this.dataManager = dataManager;
        this.objectMapper = new ObjectMapper(new YAMLFactory());
    }

    @EventListener
    @Authenticated
    public void migrateChatParametersToOllama(ApplicationStartedEvent event) {
        List<Parameters> chatParameters = dataManager.load(Parameters.class)
                .query("e.targetType = :type")
                .parameter("type", ParametersTargetType.CHAT.getId())
                .list();

        List<Parameters> changed = new ArrayList<>();
        for (Parameters parameters : chatParameters) {
            String migratedContent = migrateContent(parameters.getContent());
            if (migratedContent != null) {
                parameters.setContent(migratedContent);
                changed.add(parameters);
            }
        }

        if (changed.isEmpty()) {
            log.info("Chat parameters already use non-legacy model ids, skip Ollama migration");
            return;
        }

        dataManager.saveWithoutReload(changed.toArray());
        log.info("Migrated {} chat parameter record(s) to Ollama model id {}", changed.size(), TARGET_MODEL_NAME);
    }

    static String migrateContent(String yaml) {
        if (StringUtils.isBlank(yaml)) {
            return null;
        }
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = yamlMapper.readValue(yaml, new TypeReference<>() {});
            if (root == null) {
                return null;
            }

            Object modelObject = root.get("model");
            if (!(modelObject instanceof Map<?, ?> modelMapRaw)) {
                return null;
            }

            Object modelNameObject = modelMapRaw.get("name");
            if (!(modelNameObject instanceof String modelName) || !isLegacyModelName(modelName)) {
                return null;
            }

            Map<String, Object> rootCopy = new LinkedHashMap<>(root);
            Map<String, Object> modelCopy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : modelMapRaw.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    modelCopy.put(key, entry.getValue());
                }
            }
            modelCopy.put("name", TARGET_MODEL_NAME);
            rootCopy.put("model", modelCopy);

            Object description = rootCopy.get("description");
            if (LEGACY_DEFAULT_DESCRIPTION.equals(description)) {
                rootCopy.put("description", TARGET_DEFAULT_DESCRIPTION);
            }

            return yamlMapper.writeValueAsString(rootCopy);
        } catch (Exception e) {
            log.warn("Failed to migrate chat parameters content to Ollama model id", e);
            return null;
        }
    }

    static boolean isLegacyModelName(String modelName) {
        if (LEGACY_MODEL_NAMES.contains(modelName)) {
            return true;
        }
        String normalized = StringUtils.trimToEmpty(modelName);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.startsWith("Qwen3-Coder-30B-A3B-Instruct-") && normalized.endsWith(".gguf");
    }
}
