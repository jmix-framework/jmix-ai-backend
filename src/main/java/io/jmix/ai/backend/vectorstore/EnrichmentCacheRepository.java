package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.core.DataManager;
import io.jmix.data.exception.UniqueConstraintViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class EnrichmentCacheRepository {

    private final DataManager dataManager;

    public EnrichmentCacheRepository(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public Optional<EnrichmentCache> find(String type, String source, String jmixVersion, String modelName) {
        return dataManager.load(EnrichmentCache.class)
                .query("e.type = :type and e.source = :source and e.jmixVersion = :jmixVersion and e.modelName = :modelName")
                .parameter("type", type)
                .parameter("source", source)
                .parameter("jmixVersion", jmixVersion)
                .parameter("modelName", modelName)
                .optional();
    }

    public void save(String type, String source, String jmixVersion, String modelName,
                     String contentHash, String description, String example) {
        Optional<EnrichmentCache> existing = find(type, source, jmixVersion, modelName);
        if (existing.isPresent()) {
            save(existing.get(), contentHash, description, example);
            return;
        }

        EnrichmentCache entity = dataManager.create(EnrichmentCache.class);
        entity.setType(type);
        entity.setSource(source);
        entity.setJmixVersion(jmixVersion);
        entity.setModelName(modelName);
        try {
            save(entity, contentHash, description, example);
        } catch (UniqueConstraintViolationException e) {
            // Another ingestion run inserted the same cache key after our initial lookup.
            EnrichmentCache concurrent = find(type, source, jmixVersion, modelName)
                    .orElseThrow(() -> e);
            save(concurrent, contentHash, description, example);
        }
    }

    private void save(EnrichmentCache entity, String contentHash, String description, String example) {
        entity.setContentHash(contentHash);
        entity.setDescription(description);
        entity.setExample(example);
        dataManager.save(entity);
    }
}
