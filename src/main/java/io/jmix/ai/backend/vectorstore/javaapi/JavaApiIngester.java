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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ingests Jmix Java API reference from the Javadoc site. Each class page is parsed into
 * a structured model and rendered as a compact "API card" snippet with verbatim signatures.
 * <p>
 * This corpus ({@code javaapi}) is fully deterministic — no LLM calls.
 * {@link JavaApiEnrichedIngester} builds the parallel {@code javaapi-enriched} corpus whose
 * cards additionally carry an LLM-generated description and usage example; the corpus used by
 * the retrieval tool is selected at runtime by the {@code tools.javaapi_retriever.vectorType}
 * parameter.
 */
@Component
public class JavaApiIngester extends AbstractIngester {

    private static final Logger log = LoggerFactory.getLogger(JavaApiIngester.class);
    private static final int MAX_CARD_CHUNK_SIZE = 4_000;
    private static final String CARD_FORMAT_VERSION = "card-v3";

    private final Map<JmixVersion, String> baseUrls = new EnumMap<>(JmixVersion.class);
    private final String classListPage;
    private final Set<String> moduleWhitelist;
    private final List<String> pathBlacklist;
    private final int limit;

    private final RestTemplate restTemplate;
    private final JavadocPageParser parser = new JavadocPageParser();
    private final JavaApiCardRenderer renderer = new JavaApiCardRenderer();

    public JavaApiIngester(
            @Value("${javaapi.v2.base-url:}") String v2BaseUrl,
            @Value("${javaapi.v3.base-url:}") String v3BaseUrl,
            @Value("${javaapi.class-list-page}") String classListPage,
            @Value("${javaapi.whitelist}") String whitelist,
            @Value("${javaapi.blacklist:}") String blacklist,
            @Value("${javaapi.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            @Qualifier("ingestionRestTemplate") RestTemplate restTemplate) {
        super(vectorStore, timeSource, vectorStoreRepository, true);
        putBaseUrl(JmixVersion.V2, v2BaseUrl);
        putBaseUrl(JmixVersion.V3, v3BaseUrl);
        this.classListPage = classListPage;
        this.moduleWhitelist = Arrays.stream(whitelist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        this.pathBlacklist = Arrays.stream(blacklist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.limit = limit;
        this.restTemplate = restTemplate;
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

    /** Only versions with a configured Javadoc base URL are ingested. */
    @Override
    public List<JmixVersion> getVersions() {
        return List.copyOf(baseUrls.keySet());
    }

    @Override
    public String updateAll() {
        if (baseUrls.isEmpty()) {
            return "skipped: no Java API base URLs configured";
        }
        return super.updateAll();
    }

    @Override
    protected String currentGenerationKey() {
        return CARD_FORMAT_VERSION;
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
                .filter(this::isAllowedSource)
                .distinct()
                .toList();
    }

    boolean isAllowedSource(String source) {
        if (isPathBlacklisted(source)) {
            return false;
        }
        if (moduleWhitelist.isEmpty()) {
            return true;
        }
        String[] parts = source.split("/");
        return parts.length > 2 && moduleWhitelist.contains(parts[2]);
    }

    boolean isAllowedReference(String source, String href) {
        try {
            String resolvedPath = URI.create(source).resolve(href).getPath();
            return !isPathBlacklisted(resolvedPath);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isPathBlacklisted(String path) {
        return pathBlacklist.stream().anyMatch(path::contains);
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

        JavadocClassDoc classDoc = parser.parse(html, href -> isAllowedReference(source, href));
        if (classDoc.typeSignature().isBlank()) {
            log.warn("Not a class page, skipping: {}", url);
            return null;
        }
        Snippet card = renderer.render(classDoc, url);
        String cardText = card.format();

        Map<String, Object> metadata = createMetadata(source, cardText, version);
        metadata.put("url", url);

        return createDocument(cardText, metadata);
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> preparedDocuments = prepareCards(documents);

        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : preparedDocuments) {
            List<String> parts = JavaApiCardRenderer.splitCard(document.getText(), MAX_CARD_CHUNK_SIZE);
            if (parts.size() > 1) {
                log.debug("Split card {} into {} parts", document.getMetadata().get("url"), parts.size());
            }
            for (String part : parts) {
                Map<String, Object> metadataCopy = new HashMap<>(document.getMetadata());
                metadataCopy.put("size", part.length());
                if (shouldStampGenerationKey(document)) {
                    metadataCopy.put("generationKey", currentGenerationKey());
                }
                chunkDocs.add(createDocument(part, metadataCopy));
            }
        }
        return chunkDocs;
    }

    /** Hook for corpora that post-process rendered cards (e.g. LLM enrichment) before chunking. */
    protected List<Document> prepareCards(List<Document> documents) {
        return documents;
    }

    /**
     * Whether the card is final for the current generation; chunks of unstamped cards are
     * rebuilt by the next update.
     */
    protected boolean shouldStampGenerationKey(Document document) {
        return true;
    }
}
