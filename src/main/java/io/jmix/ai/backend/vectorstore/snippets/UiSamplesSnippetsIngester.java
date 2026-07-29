package io.jmix.ai.backend.vectorstore.snippets;

import io.jmix.ai.backend.vectorstore.CorpusType;
import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.vectorstore.uisamples.UiSamplesIngester;
import io.jmix.core.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parallel corpus to {@link UiSamplesIngester}: the same UI samples converted into small
 * context7-like snippets by an LLM ({@link SnippetizerEnricher}). Each successfully enriched
 * sample also keeps lossless source coverage chunks; failed generation keeps only retryable
 * deterministic fallback chunks.
 */
@Component
public class UiSamplesSnippetsIngester extends UiSamplesIngester {

    private static final Logger log = LoggerFactory.getLogger(UiSamplesSnippetsIngester.class);

    private final SnippetizerEnricher snippetizer;

    public UiSamplesSnippetsIngester(
            @Value("${uisamples.v2.base-url}") String v2BaseUrl,
            @Value("${uisamples.v3.base-url}") String v3BaseUrl,
            @Value("${uisamples.doc-path}") String docPath,
            @Value("${uisamples.sample-path}") String samplePath,
            @Value("${uisamples.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            @Qualifier("ingestionRestTemplate") RestTemplate restTemplate,
            SnippetizerEnricher snippetizer) {
        super(v2BaseUrl, v3BaseUrl, docPath, samplePath, limit, vectorStore, timeSource, vectorStoreRepository, restTemplate);
        this.snippetizer = snippetizer;
    }

    @Override
    public String getType() {
        return CorpusType.UISAMPLES_SNIPPETS;
    }

    @Override
    protected String currentGenerationKey() {
        return snippetizer.getGenerationKey();
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        Map<String, List<Snippet>> snippetsByDoc = snippetizer.resolveAll(getType(), documents, Document::getText);

        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Snippet> snippets = snippetsByDoc.get(document.getId());
            if (snippets == null) {
                log.warn("Falling back to deterministic chunking for {}", document.getMetadata().get("url"));
                chunkDocs.addAll(createFallbackChunks(document));
                continue;
            }
            for (Snippet snippet : snippets) {
                String text = snippet.format();
                Map<String, Object> metadata = new HashMap<>(document.getMetadata());
                metadata.put("size", text.length());
                metadata.put("enriched", "true");
                if (currentGenerationKey() != null) {
                    metadata.put("generationKey", currentGenerationKey());
                }
                chunkDocs.add(createDocument(text, metadata));
            }
            chunkDocs.addAll(createSourceChunks(document, true));
        }
        return chunkDocs;
    }

    private List<Document> createFallbackChunks(Document document) {
        return createSourceChunks(document, false);
    }

    private List<Document> createSourceChunks(Document document, boolean coverage) {
        List<Document> chunks = new ArrayList<>();
        for (String part : SnippetizerEnricher.splitContent(
                document.getText(), SnippetizerEnricher.MAX_COVERAGE_CHARS)) {
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            metadata.remove("enriched");
            if (coverage) {
                metadata.put("coverage", "true");
                metadata.put("generationKey", currentGenerationKey());
            } else {
                metadata.remove("coverage");
                metadata.remove("generationKey");
            }
            metadata.put("size", part.length());
            chunks.add(createDocument(part, metadata));
        }
        return chunks;
    }
}
