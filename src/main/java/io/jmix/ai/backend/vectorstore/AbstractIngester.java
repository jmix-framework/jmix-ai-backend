package io.jmix.ai.backend.vectorstore;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import io.jmix.ai.backend.entity.IngestionJob;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractIngester implements Ingester {

    // Conservative char-based cap to stay under embedding model token limits for mixed prose/code content.
    public static final int MAX_CHUNK_SIZE = 6_000;
    public static final int CHUNK_OVERLAP = 300;

    private final Logger log = LoggerFactory.getLogger(AbstractIngester.class);
    
    protected final VectorStore vectorStore;
    protected final TimeSource timeSource;
    protected final VectorStoreRepository vectorStoreRepository;
    protected final KnowledgeSourceManager knowledgeSourceManager;
    protected final int vectorStoreAddBatchSize;
    protected KnowledgeSourceContext knowledgeSourceContext;

    protected AbstractIngester(
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            KnowledgeSourceManager knowledgeSourceManager,
            int vectorStoreAddBatchSize) {
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
        this.vectorStoreRepository = vectorStoreRepository;
        this.knowledgeSourceManager = knowledgeSourceManager;
        this.vectorStoreAddBatchSize = vectorStoreAddBatchSize;
    }

    @Override
    public String updateAll() {
        long start = timeSource.currentTimeMillis();
        prepareUpdate();
        IngestionJob job = knowledgeSourceManager.startJob(knowledgeSourceContext);
        List<String> sources = List.of();
        try {
            sources = loadSources();
            int limit = getSourceLimit();
            log.info("Found {} sources, loading {}", sources.size(), limit > 0 ? "first " + limit : "all");

            List<Document> documents = sources.stream()
                    .limit(limit > 0 ? limit : sources.size())
                    .map(this::loadDocument)
                    .filter(this::checkContent)
                    .toList();

            log.debug("Splitting {} sources into chunks", documents.size());
            List<Document> docChunks = splitToChunks(documents);
            logChunkStats(docChunks);

            log.info("Adding {} documents to vector store", docChunks.size());
            addToVectorStoreInBatches(docChunks);

            log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);

            String result = "loaded: %d, added: %d documents in %d chunks".formatted(sources.size(), documents.size(), docChunks.size());
            knowledgeSourceManager.completeJob(job, knowledgeSourceContext, sources.size(), documents.size(), docChunks.size(), result);
            return result;
        } catch (RuntimeException ex) {
            knowledgeSourceManager.failJob(job, sources.size(), ex.getMessage());
            throw ex;
        }
    }

    protected void prepareUpdate() {
        knowledgeSourceContext = knowledgeSourceManager.resolve(getType());
    }

    protected KnowledgeSourceContext getKnowledgeSourceContext() {
        return knowledgeSourceContext;
    }

    protected String getKnowledgeSourceLocation() {
        if (knowledgeSourceContext == null) {
            return null;
        }
        return knowledgeSourceContext.knowledgeSource().getLocation();
    }

    protected String getKnowledgeSourceLanguage() {
        if (knowledgeSourceContext == null) {
            return null;
        }
        return knowledgeSourceContext.knowledgeSource().getLanguage();
    }

    @Override
    public String update(VectorStoreEntity entity) {
        prepareUpdate();

        String source = getSource(entity);
        log.info("Loading source: {}", source);
        Document document = loadDocument(source);
        if (document == null) {
            return "source not found: " + source;
        }

        if (!isContentSame(document, entity)) {
            deleteExistingEntities(entity);

            log.debug("Splitting document into chunks");
            List<Document> chunks = splitToChunks(List.of(document));
            logChunkStats(chunks);

            log.info("Adding document to vector store");
            addToVectorStoreInBatches(chunks);
            return "updated " + chunks.size() + " document";
        } else {
            return "no changes";
        }
    }

    protected boolean checkContent(Document document) {
        if (document == null) {
            return false;
        }

        String source = getSourceFromDocument(document);

        List<VectorStoreEntity> entities = vectorStoreRepository.loadList(
                buildFilterQuery(source)
        );

        if (entities.isEmpty()) {
            return true;
        }

        boolean contentChanged = false;
        for (VectorStoreEntity entity : entities) {
            if (!isContentSame(document, entity)) {
                vectorStoreRepository.delete(entity.getId());
                contentChanged = true;
            }
        }
        return contentChanged;
    }

    protected Map<String, Object> createMetadata(String source, String textContent) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", getType());
        metadata.put("source", source);
        metadata.put("sourceHash", computeHash(textContent));
        metadata.put("size", textContent.length());
        metadata.put("updated", timeSource.now().toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (knowledgeSourceContext != null) {
            metadata.put("kb", knowledgeSourceContext.knowledgeBase().getCode());
            metadata.put("sourceCode", knowledgeSourceContext.knowledgeSource().getCode());
            metadata.put("sourceId", knowledgeSourceContext.knowledgeSource().getId().toString());
            if (knowledgeSourceContext.knowledgeSource().getLanguage() != null) {
                metadata.put("language", knowledgeSourceContext.knowledgeSource().getLanguage());
            }
        }
        return metadata;
    }

    protected Document createDocument(String textContent, Map<String, Object> metadata) {
        return new Document(
                UuidProvider.createUuidV7().toString(),
                textContent,
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
        return "type == '%s' && source == '%s'".formatted(getType(), source);
    }

    protected boolean isContentSame(Document document, VectorStoreEntity entity) {
        return Objects.equals(entity.getMetadataMap().get("sourceHash"), document.getMetadata().get("sourceHash"));
    }

    protected void deleteExistingEntities(VectorStoreEntity entity) {
        String source = getSource(entity);
        List<VectorStoreEntity> entities = vectorStoreRepository.loadList(buildFilterQuery(source));
        vectorStoreRepository.delete(entities);
    }

    protected String computeHash(String content) {
        HashCode hash32 = Hashing.murmur3_32_fixed().hashString(content, StandardCharsets.UTF_8);
        return hash32.toString();
    }

    protected void logChunkStats(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        int maxSize = chunks.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        log.info("Prepared {} chunks, max chunk size={} chars", chunks.size(), maxSize);

        chunks.stream()
                .filter(document -> document.getText() != null && document.getText().length() > MAX_CHUNK_SIZE)
                .forEach(document -> log.warn("Oversized chunk after splitting: source={}, size={}",
                        document.getMetadata().get("source"),
                        document.getText().length()));
    }

    protected void addToVectorStoreInBatches(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        int effectiveBatchSize = Math.max(1, vectorStoreAddBatchSize);
        List<List<Document>> batches = buildVectorStoreBatches(documents, effectiveBatchSize);
        int totalBatches = batches.size();

        for (int i = 0; i < totalBatches; i++) {
            List<Document> batch = batches.get(i);
            long batchStart = timeSource.currentTimeMillis();
            log.info("Adding batch {}/{} to vector store ({} documents)", i + 1, totalBatches, batch.size());
            vectorStore.add(batch);
            log.info("Added batch {}/{} in {} sec", i + 1, totalBatches,
                    (timeSource.currentTimeMillis() - batchStart) / 1000.0);
        }
    }

    protected List<List<Document>> buildVectorStoreBatches(List<Document> documents, int maxBatchSize) {
        List<List<Document>> batches = new ArrayList<>();
        List<Document> currentBatch = new ArrayList<>();
        List<Document> currentSourceGroup = new ArrayList<>();
        String currentSource = null;

        for (Document document : documents) {
            String source = getSourceFromDocument(document);
            if (!Objects.equals(currentSource, source) && !currentSourceGroup.isEmpty()) {
                flushSourceGroup(batches, currentBatch, currentSourceGroup, maxBatchSize);
                currentSourceGroup = new ArrayList<>();
            }
            currentSourceGroup.add(document);
            currentSource = source;
        }

        flushSourceGroup(batches, currentBatch, currentSourceGroup, maxBatchSize);
        if (!currentBatch.isEmpty()) {
            batches.add(List.copyOf(currentBatch));
        }
        return batches;
    }

    private void flushSourceGroup(List<List<Document>> batches, List<Document> currentBatch,
                                  List<Document> sourceGroup, int maxBatchSize) {
        if (sourceGroup.isEmpty()) {
            return;
        }

        if (sourceGroup.size() > maxBatchSize) {
            if (!currentBatch.isEmpty()) {
                batches.add(List.copyOf(currentBatch));
                currentBatch.clear();
            }
            log.warn("Source group exceeds vector store batch size and will be split: source={}, chunks={}, batchSize={}",
                    getSourceFromDocument(sourceGroup.get(0)), sourceGroup.size(), maxBatchSize);
            for (int start = 0; start < sourceGroup.size(); start += maxBatchSize) {
                int end = Math.min(start + maxBatchSize, sourceGroup.size());
                batches.add(List.copyOf(sourceGroup.subList(start, end)));
            }
            return;
        }

        if (currentBatch.size() + sourceGroup.size() > maxBatchSize) {
            batches.add(List.copyOf(currentBatch));
            currentBatch.clear();
        }
        currentBatch.addAll(sourceGroup);
    }

    protected abstract List<String> loadSources();

    protected abstract int getSourceLimit();

    @Nullable
    protected abstract Document loadDocument(String source);

    protected abstract List<Document> splitToChunks(List<Document> documents);
}
