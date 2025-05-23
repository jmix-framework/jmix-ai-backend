package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;
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
public class VectorStoreDocsUpdater implements VectorStoreUpdater {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreDocsUpdater.class);
    private final String baseUrl;
    private final String initialPage;
    private final VectorStore vectorStore;
    private final TimeSource timeSource;
    private final VectorStoreRepository vectorStoreRepository;

    public VectorStoreDocsUpdater(
            @Value( "${docs.base-url}") String baseUrl,
            @Value( "${docs.initial-page}") String initialPage,
            VectorStore vectorStore,
            TimeSource timeSource, VectorStoreRepository vectorStoreRepository) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.initialPage = initialPage;
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
        this.vectorStoreRepository = vectorStoreRepository;
    }

    @Override
    public String getType() {
        return "docs";
    }

    @Override
    public String update() {
        long start = timeSource.currentTimeMillis();

        List<String> docPages = loadListOfDocPages();
        log.info("Found {} doc pages, loading them", docPages.size());

        List<org.springframework.ai.document.Document> documents = docPages.stream()
                .limit(5) // TODO remove limit
                .map(this::loadPage)
                .filter(this::isContentDifferent)
                .toList();

        log.info("Adding {} documents to vector store", documents.size());
        vectorStore.add(documents);

        log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);

        return "loaded: %d, added: %d".formatted(docPages.size(), documents.size());
    }

    private boolean isContentDifferent(org.springframework.ai.document.Document newDocument) {
        String url = (String) newDocument.getMetadata().get("url");

        List<VectorStoreEntity> entities = vectorStoreRepository.loadList(
                "type == '%s' && url == '%s'".formatted(getType(), url)
        );
        if (entities.isEmpty()) {
            return true;
        }
        boolean contentChanged = false;
        for (VectorStoreEntity entity : entities) {
            if (!entity.getContent().equals(newDocument.getText())) {
                // Delete existing document since content has changed
                vectorStoreRepository.delete(entity.getId());
                contentChanged = true;
            }
        }
        return contentChanged;
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
        metadata.put("type", getType());
        metadata.put("url", url);
        metadata.put("size", textContent.length());
        metadata.put("updated", timeSource.now().toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return metadata;
    }
}
