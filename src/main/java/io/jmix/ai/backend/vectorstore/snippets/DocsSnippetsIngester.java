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
 * context7-like snippets by an LLM ({@link SnippetizerEnricher}). Each successfully enriched page
 * also keeps lossless plain-text coverage chunks; pages for which generation fails contain only
 * retryable plain-text fallback chunks.
 */
@Component
public class DocsSnippetsIngester extends DocsIngester {

    private static final Logger log = LoggerFactory.getLogger(DocsSnippetsIngester.class);

    private final SnippetizerEnricher snippetizer;

    public DocsSnippetsIngester(
            @Value("${docs.v2.base-url}") String v2BaseUrl,
            @Value("${docs.v3.base-url}") String v3BaseUrl,
            @Value("${docs.initial-page}") String initialPage,
            @Value("${docs.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            SnippetizerEnricher snippetizer) {
        super(v2BaseUrl, v3BaseUrl, initialPage, limit, vectorStore, timeSource, vectorStoreRepository);
        this.snippetizer = snippetizer;
    }

    @Override
    public String getType() {
        return "docs-snippets";
    }

    @Override
    protected String currentGenerationKey() {
        return snippetizer.getGenerationKey();
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        Map<String, List<Snippet>> snippetsByDoc = snippetizer.resolveAll(getType(), documents,
                document -> DocsHtmlConverter.toPlainText(document.getText()));

        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Snippet> snippets = snippetsByDoc.get(document.getId());
            if (snippets == null) {
                log.warn("Falling back to plain-text chunking for {}", document.getMetadata().get("url"));
                chunkDocs.addAll(createFallbackChunks(document));
                continue;
            }
            String docPath = Objects.toString(document.getMetadata().get("docPath"), "");
            for (Snippet snippet : snippets) {
                // the Path prefix keeps post-retrieval rules working and adds topic context for embedding
                String text = "Path: " + docPath + "\n\n" + snippet.format();
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
        String docPath = Objects.toString(document.getMetadata().get("docPath"), "");
        String prefix = "Path: " + docPath + "\n\n";
        String plainText = DocsHtmlConverter.toPlainText(document.getText());

        List<Document> chunks = new ArrayList<>();
        boolean repeatPrefix = prefix.length() < SnippetizerEnricher.MAX_COVERAGE_CHARS;
        String content = repeatPrefix ? plainText : prefix + plainText;
        int chunkSize = repeatPrefix
                ? SnippetizerEnricher.MAX_COVERAGE_CHARS - prefix.length()
                : SnippetizerEnricher.MAX_COVERAGE_CHARS;
        for (String part : SnippetizerEnricher.splitContent(content, chunkSize)) {
            String text = repeatPrefix ? prefix + part : part;
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            metadata.remove("enriched");
            if (coverage) {
                metadata.put("coverage", "true");
                metadata.put("generationKey", currentGenerationKey());
            } else {
                metadata.remove("coverage");
                metadata.remove("generationKey");
            }
            metadata.put("size", text.length());
            chunks.add(createDocument(text, metadata));
        }
        return chunks;
    }
}
