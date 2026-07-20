package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.core.DataManager;
import io.jmix.core.FluentValueLoader;
import io.jmix.data.exception.UniqueConstraintViolationException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class EnrichmentCacheRepository {

    private static final int DELETE_BATCH_SIZE = 500;

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

    public void save(
            String type,
            String source,
            String jmixVersion,
            String modelName,
            String contentHash,
            String content) {
        Optional<EnrichmentCache> existing = find(type, source, jmixVersion, modelName);
        if (existing.isPresent()) {
            save(existing.get(), contentHash, content);
            return;
        }

        EnrichmentCache entity = dataManager.create(EnrichmentCache.class);
        entity.setType(type);
        entity.setSource(source);
        entity.setJmixVersion(jmixVersion);
        entity.setModelName(modelName);
        try {
            save(entity, contentHash, content);
        } catch (UniqueConstraintViolationException e) {
            // Another ingestion run inserted the same cache key after our initial lookup.
            EnrichmentCache concurrent = find(type, source, jmixVersion, modelName)
                    .orElseThrow(() -> e);
            save(concurrent, contentHash, content);
        }
    }

    public List<Generation> findGenerations() {
        return dataManager.loadValues("""
                        select e.type, e.jmixVersion, e.modelName, max(e.createdDate)
                        from EnrichmentCache e
                        group by e.type, e.jmixVersion, e.modelName
                        """)
                .properties("type", "jmixVersion", "modelName", "latestCreatedDate")
                .list().stream()
                .map(row -> new Generation(
                        row.getValue("type"),
                        row.getValue("jmixVersion"),
                        row.getValue("modelName"),
                        row.getValue("latestCreatedDate")))
                .toList();
    }

    public DeletionResult deleteGenerations(Collection<GenerationKey> generations) {
        int deletedEntries = 0;
        int deletedGenerations = 0;
        for (GenerationKey generation : generations) {
            List<UUID> ids = findIds(generation);
            if (ids.isEmpty()) {
                continue;
            }
            deletedGenerations++;
            deletedEntries += ids.size();
            for (int from = 0; from < ids.size(); from += DELETE_BATCH_SIZE) {
                int to = Math.min(from + DELETE_BATCH_SIZE, ids.size());
                Object[] references = ids.subList(from, to).stream()
                        .map(id -> dataManager.getReference(EnrichmentCache.class, id))
                        .toArray();
                dataManager.remove(references);
            }
        }
        return new DeletionResult(deletedEntries, deletedGenerations);
    }

    private List<UUID> findIds(GenerationKey generation) {
        String versionCondition = generation.jmixVersion() == null
                ? "e.jmixVersion is null"
                : "e.jmixVersion = :jmixVersion";
        FluentValueLoader<UUID> loader = dataManager.loadValue("""
                        select e.id
                        from EnrichmentCache e
                        where e.type = :type
                          and e.modelName = :modelName
                          and %s
                        """.formatted(versionCondition), UUID.class)
                .parameter("type", generation.type())
                .parameter("modelName", generation.modelName());
        if (generation.jmixVersion() != null) {
            loader.parameter("jmixVersion", generation.jmixVersion());
        }
        return loader.list();
    }

    private void save(EnrichmentCache entity, String contentHash, String content) {
        entity.setContentHash(contentHash);
        entity.setContent(content);
        dataManager.save(entity);
    }

    public record Generation(String type, String jmixVersion, String modelName,
                             OffsetDateTime latestCreatedDate) {

        public GenerationKey key() {
            return new GenerationKey(type, jmixVersion, modelName);
        }
    }

    public record GenerationKey(String type, String jmixVersion, String modelName) {
    }

    public record DeletionResult(int entries, int generations) {
    }
}
