package io.jmix.ai.backend.vectorstore;

import io.jmix.core.TimeSource;
import io.jmix.core.UuidProvider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VsDocsLoader implements VsLoader {

    private static final Logger log = LoggerFactory.getLogger(VsDocsLoader.class);
    private final String baseUrl;
    private final String initialPage;
    private final VectorStore vectorStore;
    private final TimeSource timeSource;

    public VsDocsLoader(
            @Value( "${docs.base-url}") String baseUrl,
            @Value( "${docs.initial-page}") String initialPage,
            VectorStore vectorStore,
            TimeSource timeSource) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.initialPage = initialPage;
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
    }

    @Override
    public void load() {
        long start = timeSource.currentTimeMillis();

        List<String> docPages = loadListOfDocPages();
        log.info("Found {} doc pages, loading them", docPages.size());

        List<org.springframework.ai.document.Document> documents = docPages.stream()
                .limit(10) // TODO remove limit
                .map(this::loadPage)
                .toList();

        log.info("Adding {} documents to vector store", documents.size());
        vectorStore.add(documents);

        log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);
    }

    private List<String> loadListOfDocPages() {
        String url = baseUrl + initialPage;
        Document doc;
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

    private org.springframework.ai.document.Document loadPage(String docPage) {
        String url = baseUrl + docPage;
        Document doc;
        try {
            log.debug("Loading doc page: {}", url);
            doc = Jsoup.connect(url).get();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load web page: " + url, e);
        }
        String textContent = doc.select("article.doc").text();

        return new org.springframework.ai.document.Document(
                UuidProvider.createUuidV7().toString(),
                textContent,
                createMetadata(url, textContent)
        );
    }

    private Map<String, Object> createMetadata(String url, String textContent) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "docs");
        metadata.put("url", url);
        metadata.put("size", textContent.length());
        metadata.put("updated", timeSource.now().toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return metadata;
    }
}
