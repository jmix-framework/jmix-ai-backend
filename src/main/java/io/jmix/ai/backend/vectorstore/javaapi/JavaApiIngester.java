package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ingests Jmix Java API reference from the Javadoc site. Each class page is parsed into
 * a structured model and rendered as a compact "API card" snippet with verbatim signatures.
 */
@Component
public class JavaApiIngester extends AbstractIngester {

    private static final Logger log = LoggerFactory.getLogger(JavaApiIngester.class);

    private final Map<JmixVersion, String> baseUrls = new EnumMap<>(JmixVersion.class);
    private final String classListPage;
    private final Set<String> moduleWhitelist;
    private final int limit;

    private final RestTemplate restTemplate = new RestTemplate();
    private final JavadocPageParser parser = new JavadocPageParser();
    private final JavaApiCardRenderer renderer = new JavaApiCardRenderer();

    public JavaApiIngester(
            @Value("${javaapi.v2.base-url:}") String v2BaseUrl,
            @Value("${javaapi.v3.base-url:}") String v3BaseUrl,
            @Value("${javaapi.class-list-page}") String classListPage,
            @Value("${javaapi.whitelist}") String whitelist,
            @Value("${javaapi.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        super(vectorStore, timeSource, vectorStoreRepository, true);
        putBaseUrl(JmixVersion.V2, v2BaseUrl);
        putBaseUrl(JmixVersion.V3, v3BaseUrl);
        this.classListPage = classListPage;
        this.moduleWhitelist = Arrays.stream(whitelist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        this.limit = limit;
    }

    private void putBaseUrl(JmixVersion version, String baseUrl) {
        if (!baseUrl.isBlank()) {
            baseUrls.put(version, baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        }
    }

    @Override
    public String getType() {
        return "javaapi";
    }

    /**
     * Only versions with a configured base URL (e.g. Javadoc for Jmix 3 is not published yet).
     */
    @Override
    public List<JmixVersion> getVersions() {
        return List.copyOf(baseUrls.keySet());
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    @Override
    protected List<String> loadSources(JmixVersion version) {
        String url = baseUrls.get(version) + classListPage;
        String html;
        try {
            html = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load class list from: " + url, e);
        }
        if (html == null) {
            throw new RuntimeException("Empty class list page: " + url);
        }
        return Jsoup.parse(html).select("a[href^=io/jmix/]").stream()
                .map(a -> a.attr("href"))
                .filter(href -> href.endsWith(".html"))
                .filter(this::isWhitelisted)
                .distinct()
                .toList();
    }

    private boolean isWhitelisted(String source) {
        if (moduleWhitelist.isEmpty()) {
            return true;
        }
        String[] parts = source.split("/");
        return parts.length > 2 && moduleWhitelist.contains(parts[2]);
    }

    @Override
    protected Document loadDocument(String source, JmixVersion version) {
        String url = baseUrls.get(version) + source;
        log.debug("Loading javadoc page: {}", url);

        String html;
        try {
            html = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.warn("Failed to load javadoc page: {}", url);
            return null;
        }
        if (html == null) {
            log.warn("Empty javadoc page: {}", url);
            return null;
        }

        JavadocClassDoc classDoc = parser.parse(html);
        if (classDoc.typeSignature().isBlank()) {
            log.warn("Not a class page, skipping: {}", url);
            return null;
        }
        Snippet card = renderer.render(classDoc, url);
        String textContent = card.format();

        Map<String, Object> metadata = createMetadata(source, textContent, version);
        metadata.put("url", url);
        metadata.put("className", classDoc.fullyQualifiedName());

        return createDocument(textContent, metadata);
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<String> parts = JavaApiCardRenderer.splitCard(document.getText(), MAX_CHUNK_SIZE);
            if (parts.size() > 1) {
                log.debug("Split card {} into {} parts", document.getMetadata().get("url"), parts.size());
            }
            for (String part : parts) {
                Map<String, Object> metadataCopy = new java.util.HashMap<>(document.getMetadata());
                metadataCopy.put("size", part.length());
                chunkDocs.add(createDocument(part, metadataCopy));
            }
        }
        return chunkDocs;
    }
}
