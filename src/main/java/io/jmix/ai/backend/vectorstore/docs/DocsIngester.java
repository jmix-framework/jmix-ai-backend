package io.jmix.ai.backend.vectorstore.docs;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.vectorstore.Chunker;
import io.jmix.core.TimeSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
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

    private final Map<JmixVersion, String> baseUrls;
    private final String initialPage;
    private final int limit;
    private final Chunker chunker;

    public DocsIngester(
            @Value("${docs.v2.base-url}") String v2BaseUrl,
            @Value("${docs.v3.base-url}") String v3BaseUrl,
            @Value("${docs.initial-page}") String initialPage,
            @Value("${docs.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        super(vectorStore, timeSource, vectorStoreRepository, true);
        this.baseUrls = Map.of(
                JmixVersion.V2, ensureTrailingSlash(v2BaseUrl),
                JmixVersion.V3, ensureTrailingSlash(v3BaseUrl));
        this.initialPage = initialPage;
        this.limit = limit;
        this.chunker = new DocsChunker(MAX_CHUNK_SIZE, 400, 300);
    }

    private static String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private String baseUrl(JmixVersion version) {
        return baseUrls.get(version);
    }

    @Override
    public String getType() {
        return "docs";
    }

    @Override
    protected List<String> loadSources(JmixVersion version) {
        String url = baseUrl(version) + initialPage;
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
    protected Document loadDocument(String source, JmixVersion version) {
        String url = baseUrl(version) + source;
        log.debug("Loading doc page: {}", url);

        org.jsoup.nodes.Document doc;
        try {
            doc = Jsoup.connect(url).get();
        } catch (IOException e) {
            log.warn("Failed to load web page: {}", url);
            return null;
        }

        Elements elements = doc.select("article.doc");
        elements.select("nav.pagination").remove();
        elements.select("div.feedback-form").remove();
        String htmlContent = elements.outerHtml();

        Elements pathElements = doc.select("nav.breadcrumbs");
        String docPath = pathElements.select("ul li")
                .stream()
                .skip(1)
                .map(Element::text)
                .collect(Collectors.joining(" > "));

        Map<String, Object> metadata = createMetadata(source, htmlContent, version);
        metadata.put("docPath", docPath);
        metadata.put("url", url);

        return createDocument(htmlContent, metadata);
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            String url = (String) metadata.get("url");
            String docPath = (String) metadata.get("docPath");

            log.debug("Splitting doc: {}", url);
            List<Chunker.Chunk> chunks = chunker.extract(document.getText(), docPath);

            for (Chunker.Chunk chunk : chunks) {
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
