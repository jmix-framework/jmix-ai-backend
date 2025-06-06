package io.jmix.ai.backend.vectorstore;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.TimeSource;
import io.jmix.core.UuidProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractRetriever implements Retriever {

    private final Logger log = LoggerFactory.getLogger(AbstractRetriever.class);
    
    protected final VectorStore vectorStore;
    protected final TimeSource timeSource;
    protected final VectorStoreRepository vectorStoreRepository;
    protected final TextSplitter textSplitter;

    // A chunk size of 4000 tokens is roughly half the OpenAI limit (8192), providing a safe buffer for
    // metadata, formatting, or encoding variations. For a 100KB document (~16,667-20,000 tokens),
    // this would yield ~4-5 chunks, which is manageable for retrieval and maintains sufficient context.
    // For a 20KB document (~3,333-4,000 tokens), it results in 1-2 chunks, which is efficient.
    private static final int DEFAULT_CHUNK_SIZE = 4000;

    // Assuming 5-6 characters per token, 1000 characters is ~167-200 tokens, ensuring chunks are substantial
    // enough to capture meaningful content (e.g., a few sentences or a paragraph). This is higher than
    // the default (350) to avoid overly small chunks for larger documents, which may contain
    // structured content like headings or lists.
    private static final int MIN_CHUNK_SIZE_CHARS = 1000;

    // This ensures that only chunks with sufficient content (e.g., a sentence or two) are embedded,
    // filtering out trivial fragments. A value of 50 tokens (~250-300 characters) is reasonable for documents,
    // which may have short sections or list items that should still be included.
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 50;

    // A 100KB document (~16,667-20,000 tokens) split into 4000-token chunks could produce ~4-5 chunks,
    // but larger or more complex documents might generate more. Setting maxNumChunks to 100 provides ample
    // headroom for larger documents while preventing excessive splitting. This is much lower than the default (10,000),
    // which is unnecessarily high for the use case.
    private static final int MAX_NUM_CHUNKS = 100;

    private static final boolean KEEP_SEPARATOR = true;

    protected AbstractRetriever(
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
        this.vectorStoreRepository = vectorStoreRepository;
        this.textSplitter = createTextSplitter();
    }

    protected TextSplitter createTextSplitter() {
        return new TokenTextSplitter(
                DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, KEEP_SEPARATOR);
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
        List<Document> chunks = textSplitter.apply(documents);

        log.info("Adding {} documents to vector store", chunks.size());
        vectorStore.add(chunks);

        log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);

        return "loaded: %d, added: %d documents in %d chunks".formatted(sources.size(), documents.size(), chunks.size());
    }

    protected void prepareUpdate() {
    }

    @Override
    public String update(VectorStoreEntity entity) {
        prepareUpdate();

        String source = getSource(entity);
        log.info("Loading source: {}", source);
        Document document = loadDocument(source);
        
        if (!isContentSame(document, entity)) {
            deleteExistingEntities(entity);

            log.debug("Splitting document into chunks");
            List<Document> chunks = textSplitter.apply(List.of(document));

            log.info("Adding document to vector store");
            vectorStore.add(chunks);
            return "updated " + chunks.size() + " document";
        } else {
            return "no changes";
        }
    }

    protected boolean checkContent(Document document) {
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

    protected abstract Document loadDocument(String source);
}