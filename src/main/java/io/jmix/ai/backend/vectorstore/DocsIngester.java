package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.vectorstore.chunking.Chunk;
import io.jmix.ai.backend.vectorstore.chunking.Chunker;
import io.jmix.ai.backend.vectorstore.chunking.DocsChunker;
import io.jmix.core.TimeSource;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DocsIngester extends AbstractIngester {

    private static final Logger log = LoggerFactory.getLogger(DocsIngester.class);

    private final String baseUrl;
    private final String initialPage;
    private final int limit;
    protected final Chunker chunker;

    public DocsIngester(
            @Value("${docs.base-url}") String baseUrl,
            @Value("${docs.initial-page}") String initialPage,
            @Value("${docs.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        super(vectorStore, timeSource, vectorStoreRepository);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.initialPage = initialPage;
        this.limit = limit;
        this.chunker = createChunker();
    }

    protected Chunker createChunker() {
        return new DocsChunker(20_000, 400, 300);
    }

    @Override
    public String getType() {
        return "docs";
    }

    @Override
    protected List<String> loadSources() {
        String url = baseUrl + initialPage;
        org.jsoup.nodes.Document doc;
        try {
            doc = Jsoup.connect(url).get();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load web page: " + url, e);
        }
        Elements navLinks = doc.select("a.nav-link");
        return navLinks.stream()
                .map(element -> element.attr("href"))
                .toList();
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    @Override
    protected Document loadDocument(String source) {
        String url = baseUrl + source;
        log.debug("Loading doc page: {}", url);

        org.jsoup.nodes.Document doc;
        try {
            doc = Jsoup.connect(url).get();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load web page: " + url, e);
        }

        Elements elements = doc.select("article.doc");
        elements.select("nav.pagination").remove();
        elements.select("div.feedback-form").remove();
        String htmlContent = elements.outerHtml();

        Map<String, Object> metadata = createMetadata(source, htmlContent);
        metadata.put("url", url);

        return createDocument(htmlContent, metadata);
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Chunk> chunks = chunker.extract(document.getText());

            Map<String, Object> metadata = document.getMetadata();
            String url = (String) metadata.get("url");

            for (Chunk chunk : chunks) {
                Map<String, Object> metadataCopy = copyMetadata(metadata);
                metadataCopy.put("size", chunk.text().length());
                if (chunk.anchor() != null) {
                    metadataCopy.put("url", url + chunk.anchor());
                }
                Document chunkDoc = createDocument(chunk.text(), metadataCopy);
                chunkDocs.add(chunkDoc);
            }
        }
        return chunkDocs;
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        return metadata.entrySet()
                .stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
