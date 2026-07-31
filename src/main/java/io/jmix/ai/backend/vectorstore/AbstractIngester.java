package io.jmix.ai.backend.vectorstore;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.TimeSource;
import io.jmix.core.UuidProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class AbstractIngester implements Ingester {

    // 30_000 (~16 text pages) is about 6000 tokens, which is less than the OpenAI limit (8192).
    public static final int MAX_CHUNK_SIZE = 30_000;
    private static final String INGESTION_ID = "ingestionId";
    private static final String INGESTION_CHUNK_COUNT = "ingestionChunkCount";

    private final Logger log = LoggerFactory.getLogger(AbstractIngester.class);

    protected final VectorStore vectorStore;
    protected final TimeSource timeSource;
    protected final VectorStoreRepository vectorStoreRepository;
    protected final boolean versionScoped;

    protected AbstractIngester(
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            boolean versionScoped) {
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
        this.vectorStoreRepository = vectorStoreRepository;
        this.versionScoped = versionScoped;
    }

    @Override
    public List<JmixVersion> getVersions() {
        return versionScoped ? List.of(JmixVersion.V2, JmixVersion.V3) : List.of();
    }

    @Override
    public synchronized String updateAll() {
        if (versionScoped) {
            throw new IllegalStateException(
                    "Jmix version is required to update version-scoped ingester '" + getType() + "'");
        }
        return doUpdateAll(null);
    }

    @Override
    public synchronized String updateAll(JmixVersion version) {
        return doUpdateAll(versionScoped ? version : null);
    }

    private String doUpdateAll(@Nullable JmixVersion version) {
        long start = timeSource.currentTimeMillis();

        prepareUpdate(version);

        List<String> sources = loadSources(version);
        int limit = getSourceLimit();
        log.info("Found {} sources, loading {}", sources.size(), limit > 0 ? "first " + limit : "all");

        List<SourceUpdate> updates = new ArrayList<>();
        sources.stream()
                .limit(limit > 0 ? limit : sources.size())
                .map(source -> loadDocument(source, version))
                .filter(Objects::nonNull)
                .map(document -> planUpdate(document, version))
                .filter(Objects::nonNull)
                .forEach(updates::add);

        List<Document> documents = updates.stream().map(SourceUpdate::document).toList();

        log.debug("Splitting {} sources into chunks", documents.size());
        List<Document> docChunks = documents.isEmpty()
                ? List.of()
                : prepareChunks(splitToChunks(documents), updates);
        Set<String> completedSources = docChunks.stream()
                .map(this::getSourceFromDocument)
                .collect(Collectors.toSet());
        int skippedSources = updates.size() - completedSources.size();
        if (skippedSources > 0) {
            log.warn("No chunks generated for {} sources; keeping their previous chunks", skippedSources);
        }

        if (!docChunks.isEmpty()) {
            log.info("Adding {} documents to vector store", docChunks.size());
            addNewGeneration(docChunks);
            deletePreviousGenerations(updates, completedSources);
        }

        log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);

        return "loaded: %d, added: %d documents in %d chunks"
                .formatted(sources.size(), completedSources.size(), docChunks.size());
    }

    protected void prepareUpdate(@Nullable JmixVersion version) {
        prepareUpdate();
    }

    protected void prepareUpdate() {
    }

    @Override
    public synchronized String update(VectorStoreEntity entity) {
        JmixVersion version = versionScoped
                ? JmixVersion.fromId((String) entity.getMetadataMap().get("jmixVersion"))
                : null;
        if (versionScoped && version == null) {
            return "cannot update: missing jmixVersion metadata";
        }
        prepareUpdate(version);

        String source = getSource(entity);
        log.info("Loading source: {}", source);

        Document document = loadDocument(source, version);
        if (document == null) {
            return "source not found: " + source;
        }

        SourceUpdate update = planUpdate(document, version);
        if (update != null) {
            log.debug("Splitting document into chunks");
            List<Document> chunks = prepareChunks(splitToChunks(List.of(document)), List.of(update));
            if (chunks.isEmpty()) {
                return "not updated: no chunks generated";
            }

            log.info("Adding document to vector store");
            addNewGeneration(chunks);
            deletePreviousGenerations(List.of(update), Set.of(source));
            return "updated " + chunks.size() + " document";
        } else {
            return "no changes";
        }
    }

    @Nullable
    private SourceUpdate planUpdate(Document document, @Nullable JmixVersion version) {
        String source = getSourceFromDocument(document);
        List<VectorStoreEntity> entities = vectorStoreRepository.loadList(
                buildFilterQuery(source, version)
        );
        if (entities.isEmpty()) {
            return new SourceUpdate(document, List.of());
        }

        List<VectorStoreEntity> completeGeneration = findCompleteGeneration(document, entities);
        if (completeGeneration == null) {
            return new SourceUpdate(document, entityIds(entities));
        }

        Set<UUID> retainedIds = completeGeneration.stream()
                .map(VectorStoreEntity::getId)
                .collect(Collectors.toSet());
        List<VectorStoreEntity> redundant = entities.stream()
                .filter(entity -> !retainedIds.contains(entity.getId()))
                .toList();
        if (!redundant.isEmpty()) {
            log.info("Removing {} redundant chunks for {}", redundant.size(), source);
            vectorStoreRepository.deleteIds(entityIds(redundant));
        }
        return null;
    }

    private static List<UUID> entityIds(List<VectorStoreEntity> entities) {
        return entities.stream().map(VectorStoreEntity::getId).toList();
    }

    @Nullable
    private List<VectorStoreEntity> findCompleteGeneration(
            Document document, List<VectorStoreEntity> entities) {
        Map<String, List<VectorStoreEntity>> byIngestion = new LinkedHashMap<>();
        for (VectorStoreEntity entity : entities) {
            if (isContentSame(document, entity)) {
                String ingestionId = Objects.toString(entity.getMetadataMap().get(INGESTION_ID), "");
                byIngestion.computeIfAbsent(ingestionId, ignored -> new ArrayList<>()).add(entity);
            }
        }

        for (Map.Entry<String, List<VectorStoreEntity>> entry : byIngestion.entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue;
            }
            Object expectedValue = entry.getValue().getFirst().getMetadataMap().get(INGESTION_CHUNK_COUNT);
            if (expectedValue instanceof Number expected && entry.getValue().size() == expected.intValue()) {
                return entry.getValue();
            }
        }

        List<VectorStoreEntity> legacy = byIngestion.get("");
        return currentGenerationKey() == null && legacy != null ? legacy : null;
    }

    protected Map<String, Object> createMetadata(String source, String textContent) {
        return createMetadata(source, textContent, null);
    }

    protected Map<String, Object> createMetadata(String source, String textContent, @Nullable JmixVersion version) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", getType());
        metadata.put("source", source);
        metadata.put("sourceHash", computeHash(textContent));
        metadata.put("size", textContent.length());
        metadata.put("updated", timeSource.now().toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (versionScoped && version != null) {
            metadata.put("jmixVersion", version.getId());
        }
        return metadata;
    }

    protected Document createDocument(String textContent, Map<String, Object> metadata) {
        return new Document(
                UuidProvider.createUuidV7().toString(),
                // transport guard for every corpus: PostgreSQL TEXT rejects NUL, and source content
                // fetched over HTTP or read from files is not guaranteed free of it
                NormalizationUtils.stripNul(textContent),
                metadata
        );
    }

    protected String getSource(VectorStoreEntity entity) {
        return (String) entity.getMetadataMap().get("source");
    }

    protected String getSourceFromDocument(Document document) {
        return (String) document.getMetadata().get("source");
    }

    protected String buildFilterQuery(String source) {
        return buildFilterQuery(source, null);
    }

    protected String buildFilterQuery(String source, @Nullable JmixVersion version) {
        if (versionScoped && version != null) {
            return "type == '%s' && source == '%s' && jmixVersion == '%s'"
                    .formatted(getType(), source, version.getId());
        }
        return "type == '%s' && source == '%s'".formatted(getType(), source);
    }

    protected boolean isContentSame(Document document, VectorStoreEntity entity) {
        return Objects.equals(entity.getMetadataMap().get("sourceHash"), document.getMetadata().get("sourceHash"))
                && Objects.equals(currentGenerationKey(), entity.getMetadataMap().get("generationKey"))
                && Objects.equals(pageUrl(document.getMetadata().get("url")),
                        pageUrl(entity.getMetadataMap().get("url")));
    }

    /**
     * The chunk's page location for change detection: its url metadata without the section
     * anchor that chunkers may append. A source whose url changed while the content stayed the
     * same (e.g. a docs site move) must be re-ingested, or its chunks would cite the old
     * location forever.
     */
    @Nullable
    private static String pageUrl(@Nullable Object url) {
        if (url == null) {
            return null;
        }
        String text = url.toString();
        int anchor = text.indexOf('#');
        return anchor < 0 ? text : text.substring(0, anchor);
    }

    /**
     * For LLM-generated corpuses, a key identifying the current generator config (model + prompt
     * version). Stored in each chunk's {@code generationKey} metadata and compared in
     * {@link #isContentSame}, so bumping the prompt/model rebuilds existing docs on the next update.
     * Returns {@code null} for deterministic corpuses (no rebuild on config change).
     */
    @Nullable
    protected String currentGenerationKey() {
        return null;
    }

    private List<Document> prepareChunks(List<Document> chunks, List<SourceUpdate> updates) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        Set<String> expectedSources = updates.stream()
                .map(SourceUpdate::document)
                .map(this::getSourceFromDocument)
                .collect(Collectors.toSet());
        Map<String, Integer> chunkCounts = new HashMap<>();
        for (Document chunk : chunks) {
            String source = getSourceFromDocument(chunk);
            if (source == null) {
                throw new IllegalStateException("Generated chunk has no source metadata");
            }
            if (!expectedSources.contains(source)) {
                throw new IllegalStateException("Generated chunk has unexpected source " + source);
            }
            if (!Objects.equals(getType(), chunk.getMetadata().get("type"))) {
                throw new IllegalStateException("Generated chunk has invalid type metadata for " + source);
            }
            chunkCounts.merge(source, 1, Integer::sum);
        }

        String ingestionId = UuidProvider.createUuidV7().toString();
        return chunks.stream().map(chunk -> {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put(INGESTION_ID, ingestionId);
            metadata.put(INGESTION_CHUNK_COUNT, chunkCounts.get(getSourceFromDocument(chunk)));
            return chunk.mutate().metadata(metadata).build();
        }).toList();
    }

    private void addNewGeneration(List<Document> chunks) {
        String ingestionId = (String) chunks.getFirst().getMetadata().get(INGESTION_ID);
        try {
            vectorStore.add(chunks);
        } catch (RuntimeException | Error failure) {
            try {
                vectorStoreRepository.delete("type == '%s' && %s == '%s'"
                        .formatted(getType(), INGESTION_ID, ingestionId));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                log.error("Failed to clean up incomplete ingestion {}", ingestionId, cleanupFailure);
            }
            throw failure;
        }
    }

    private void deletePreviousGenerations(List<SourceUpdate> updates, Set<String> completedSources) {
        for (SourceUpdate update : updates) {
            if (completedSources.contains(getSourceFromDocument(update.document()))
                    && !update.previousIds().isEmpty()) {
                vectorStoreRepository.deleteIds(update.previousIds());
            }
        }
    }

    protected String computeHash(String content) {
        HashCode hash32 = Hashing.murmur3_32_fixed().hashString(content, StandardCharsets.UTF_8);
        return hash32.toString();
    }

    protected List<String> loadSources(@Nullable JmixVersion version) {
        return loadSources();
    }

    protected List<String> loadSources() {
        return List.of();
    }

    protected abstract int getSourceLimit();

    @Nullable
    protected Document loadDocument(String source, @Nullable JmixVersion version) {
        return loadDocument(source);
    }

    @Nullable
    protected Document loadDocument(String source) {
        return null;
    }

    protected abstract List<Document> splitToChunks(List<Document> documents);

    private record SourceUpdate(Document document, List<UUID> previousIds) {
    }
}
