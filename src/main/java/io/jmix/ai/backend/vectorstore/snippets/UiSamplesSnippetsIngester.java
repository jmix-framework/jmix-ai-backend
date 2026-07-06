package io.jmix.ai.backend.vectorstore.snippets;

import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.vectorstore.uisamples.UiSamplesIngester;
import io.jmix.core.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parallel corpus to {@link UiSamplesIngester}: the same UI samples converted into small
 * context7-like snippets by an LLM ({@link SnippetizerEnricher}). Samples for which generation
 * fails fall back to the whole-sample document.
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
            SnippetizerEnricher snippetizer) {
        super(v2BaseUrl, v3BaseUrl, docPath, samplePath, limit, vectorStore, timeSource, vectorStoreRepository);
        this.snippetizer = snippetizer;
    }

    @Override
    public String getType() {
        return "uisamples-snippets";
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        Map<String, List<Snippet>> snippetsByDoc = snippetizer.resolveAll(getType(), documents);

        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Snippet> snippets = snippetsByDoc.get(document.getId());
            if (snippets == null) {
                log.warn("Falling back to whole-sample document for {}", document.getMetadata().get("url"));
                chunkDocs.addAll(super.splitToChunks(List.of(document)));
                continue;
            }
            for (Snippet snippet : snippets) {
                String text = snippet.format();
                Map<String, Object> metadata = new HashMap<>(document.getMetadata());
                metadata.put("size", text.length());
                metadata.put("enriched", "true");
                chunkDocs.add(createDocument(text, metadata));
            }
        }
        return chunkDocs;
    }
}
