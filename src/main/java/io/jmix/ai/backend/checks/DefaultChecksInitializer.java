package io.jmix.ai.backend.checks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.core.Resources;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.Authenticated;
import io.jmix.data.PersistenceHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class DefaultChecksInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultChecksInitializer.class);
    private static final String DEFAULT_CHECKS_RESOURCE = "io/jmix/ai/backend/init/check-defs.json";

    private final UnconstrainedDataManager dataManager;
    private final Resources resources;
    private final ObjectMapper objectMapper;

    public DefaultChecksInitializer(UnconstrainedDataManager dataManager,
                                    Resources resources,
                                    ObjectMapper objectMapper) {
        this.dataManager = dataManager;
        this.resources = resources;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Authenticated
    public void initDefaultChecks(ApplicationStartedEvent event) {
        String json = resources.getResourceAsString(DEFAULT_CHECKS_RESOURCE);
        if (json == null) {
            log.error("Failed to load default checks from {}", DEFAULT_CHECKS_RESOURCE);
            return;
        }

        Set<UUID> existingIds = dataManager.load(CheckDef.class)
                .query("select e from CheckDef e")
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list().stream()
                .map(CheckDef::getId)
                .collect(java.util.stream.Collectors.toSet());

        List<DefaultCheck> missing = missingDefinitions(json, existingIds, objectMapper);
        if (missing.isEmpty()) {
            log.info("All default checks already exist");
            return;
        }

        List<CheckDef> entities = missing.stream()
                .map(this::createEntity)
                .toList();
        dataManager.save(entities.toArray());
        log.info("Imported {} missing default checks", entities.size());
    }

    private CheckDef createEntity(DefaultCheck definition) {
        CheckDef entity = dataManager.create(CheckDef.class);
        entity.setId(definition.id());
        entity.setActive(definition.active());
        entity.setCategory(definition.category());
        entity.setQuestion(definition.question());
        entity.setAnswer(definition.answer());
        return entity;
    }

    static List<DefaultCheck> missingDefinitions(String json, Set<UUID> existingIds, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse default checks", e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("Default checks resource must contain a JSON array");
        }

        Set<UUID> knownIds = new HashSet<>(existingIds);
        Set<UUID> resourceIds = new HashSet<>();
        List<DefaultCheck> missing = new ArrayList<>();
        for (JsonNode node : root) {
            UUID id = UUID.fromString(node.path("id").asText());
            if (!resourceIds.add(id)) {
                throw new IllegalArgumentException("Duplicate default check id: " + id);
            }
            if (knownIds.add(id)) {
                missing.add(new DefaultCheck(
                        id,
                        node.path("active").asBoolean(false),
                        text(node, "category"),
                        text(node, "question"),
                        text(node, "answer")));
            }
        }
        return List.copyOf(missing);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    record DefaultCheck(UUID id, Boolean active, String category, String question, String answer) {
    }
}
