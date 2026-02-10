package io.jmix.ai.backend.vectorstore;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractIngester implements Ingester {

    // 30_000 (~16 text pages) is about 6000 tokens, which is less than the OpenAI limit (8192).
    public static final int MAX_CHUNK_SIZE = 10_000;

    private final Logger log = LoggerFactory.getLogger(AbstractIngester.class);
    
    protected final VectorStore vectorStore;
    protected final TimeSource timeSource;
    protected final VectorStoreRepository vectorStoreRepository;

    protected AbstractIngester(
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
        this.vectorStoreRepository = vectorStoreRepository;
    }

    @Override
    public String updateAll() {
        long start = timeSource.currentTimeMillis();

        prepareUpdate();

        List<String> sources = loadSources();
        int limit = getSourceLimit();
        log.info("Found {} sources, loading {}", sources.size(), limit > 0 ? "first " + limit : "all");

        List<Document> documents = sources.stream()
                .limit(limit > 0 ? limit : sources.size())
                .map(this::loadDocument)
                .filter(this::checkContent)
                .toList();

        log.debug("Splitting {} sources into chunks", documents.size());
        List<Document> docChunks = splitToChunks(documents);

        log.info("Adding {} documents to vector store", docChunks.size());
        vectorStore.add(docChunks);

        log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);

        return "loaded: %d, added: %d documents in %d chunks".formatted(sources.size(), documents.size(), docChunks.size());
    }

    protected void prepareUpdate() {
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

            log.info("Adding document to vector store");
            vectorStore.add(chunks);
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

    protected abstract List<String> loadSources();

    protected abstract int getSourceLimit();

    @Nullable
    protected abstract Document loadDocument(String source);

    protected abstract List<Document> splitToChunks(List<Document> documents);
}