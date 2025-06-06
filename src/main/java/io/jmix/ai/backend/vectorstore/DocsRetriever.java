package io.jmix.ai.backend.vectorstore;

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
import java.util.List;
import java.util.Map;

@Component
public class DocsRetriever extends AbstractRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocsRetriever.class);

    private final String baseUrl;
    private final String initialPage;
    private final int limit;

    public DocsRetriever(
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
        String textContent = elements.text();

        Map<String, Object> metadata = createMetadata(source, textContent);
        metadata.put("url", url);

        return createDocument(textContent, metadata);
    }
}
