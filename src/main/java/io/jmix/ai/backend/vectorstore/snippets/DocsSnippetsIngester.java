package io.jmix.ai.backend.vectorstore.snippets;

import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.vectorstore.docs.DocsIngester;
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
import java.util.Objects;

/**
 * Parallel corpus to {@link DocsIngester}: the same documentation pages converted into small
 * context7-like snippets by an LLM ({@link SnippetizerEnricher}). Pages for which generation
 * fails fall back to the regular docs chunking, so the corpus always covers every page.
 */
@Component
public class DocsSnippetsIngester extends DocsIngester {

    private static final Logger log = LoggerFactory.getLogger(DocsSnippetsIngester.class);

    private final SnippetizerEnricher snippetizer;

    public DocsSnippetsIngester(
            @Value("${docs.v2.base-url}") String baseUrl,
            @Value("${docs.initial-page}") String initialPage,
            @Value("${docs.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            SnippetizerEnricher snippetizer) {
        super(baseUrl, initialPage, limit, vectorStore, timeSource, vectorStoreRepository);
        this.snippetizer = snippetizer;
    }

    @Override
    public String getType() {
        return "docs-snippets";
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        Map<String, List<Snippet>> snippetsByDoc = snippetizer.resolveAll(getType(), documents,
                document -> DocsHtmlConverter.toPlainText(document.getText()));

        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Snippet> snippets = snippetsByDoc.get(document.getId());
            if (snippets == null) {
                log.warn("Falling back to regular chunking for {}", document.getMetadata().get("url"));
                chunkDocs.addAll(super.splitToChunks(List.of(document)));
                continue;
            }
            String docPath = Objects.toString(document.getMetadata().get("docPath"), "");
            for (Snippet snippet : snippets) {
                // the Path prefix keeps post-retrieval rules working and adds topic context for embedding
                String text = "Path: " + docPath + "\n\n" + snippet.format();
                Map<String, Object> metadata = new HashMap<>(document.getMetadata());
                metadata.put("size", text.length());
                metadata.put("enriched", "true");
                chunkDocs.add(createDocument(text, metadata));
            }
        }
        return chunkDocs;
    }
}
