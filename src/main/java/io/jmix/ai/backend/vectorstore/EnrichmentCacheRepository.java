package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class EnrichmentCacheRepository {

    private final DataManager dataManager;

    public EnrichmentCacheRepository(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public Optional<EnrichmentCache> find(String type, String source, String modelName) {
        return dataManager.load(EnrichmentCache.class)
                .query("e.type = :type and e.source = :source and e.modelName = :modelName")
                .parameter("type", type)
                .parameter("source", source)
                .parameter("modelName", modelName)
                .optional();
    }

    public void save(String type, String source, String modelName,
                     String contentHash, String description, String example) {
        EnrichmentCache entity = find(type, source, modelName)
                .orElseGet(() -> {
                    EnrichmentCache created = dataManager.create(EnrichmentCache.class);
                    created.setType(type);
                    created.setSource(source);
                    created.setModelName(modelName);
                    return created;
                });
        entity.setContentHash(contentHash);
        entity.setDescription(description);
        entity.setExample(example);
        dataManager.save(entity);
    }
}
