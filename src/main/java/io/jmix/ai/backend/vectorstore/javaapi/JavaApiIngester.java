package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Ingests Jmix Java API reference from the Javadoc site. Each class page is parsed into
 * a structured model and rendered as a compact "API card" snippet with verbatim signatures.
 * <p>
 * When enrichment is enabled, cards of new or changed pages (they are the only ones that reach
 * {@link #splitToChunks}) get an LLM-generated description and usage example. Generation runs
 * in parallel; cache lookups and saves stay on the caller thread, which has the Jmix security
 * context required by DataManager.
 */
@Component
public class JavaApiIngester extends AbstractIngester {

    private static final Logger log = LoggerFactory.getLogger(JavaApiIngester.class);

    private final Map<JmixVersion, String> baseUrls = new EnumMap<>(JmixVersion.class);
    private final String classListPage;
    private final Set<String> moduleWhitelist;
    private final int limit;
    private final int enrichmentParallelism;

    private final RestTemplate restTemplate = new RestTemplate();
    private final JavadocPageParser parser = new JavadocPageParser();
    private final JavaApiCardRenderer renderer = new JavaApiCardRenderer();
    private final JavaApiEnricher enricher;
    private final EnrichmentCacheRepository enrichmentCacheRepository;

    public JavaApiIngester(
            @Value("${javaapi.v2.base-url:}") String v2BaseUrl,
            @Value("${javaapi.v3.base-url:}") String v3BaseUrl,
            @Value("${javaapi.class-list-page}") String classListPage,
            @Value("${javaapi.whitelist}") String whitelist,
            @Value("${javaapi.limit}") int limit,
            @Value("${javaapi.enrichment.parallelism}") int enrichmentParallelism,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            JavaApiEnricher enricher,
            EnrichmentCacheRepository enrichmentCacheRepository) {
        super(vectorStore, timeSource, vectorStoreRepository, true);
        putBaseUrl(JmixVersion.V2, v2BaseUrl);
        putBaseUrl(JmixVersion.V3, v3BaseUrl);
        this.classListPage = classListPage;
        this.moduleWhitelist = Arrays.stream(whitelist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        this.limit = limit;
        this.enrichmentParallelism = Math.max(1, enrichmentParallelism);
        this.enricher = enricher;
        this.enrichmentCacheRepository = enrichmentCacheRepository;
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
        String cardText = card.format();

        Map<String, Object> metadata = createMetadata(source, cardText, version);
        metadata.put("url", url);
        metadata.put("className", classDoc.fullyQualifiedName());

        return createDocument(cardText, metadata);
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> enrichedDocuments = enrichDocuments(documents);

        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : enrichedDocuments) {
            List<String> parts = JavaApiCardRenderer.splitCard(document.getText(), MAX_CHUNK_SIZE);
            if (parts.size() > 1) {
                log.debug("Split card {} into {} parts", document.getMetadata().get("url"), parts.size());
            }
            for (String part : parts) {
                Map<String, Object> metadataCopy = new HashMap<>(document.getMetadata());
                metadataCopy.put("size", part.length());
                chunkDocs.add(createDocument(part, metadataCopy));
            }
        }
        return chunkDocs;
    }

    private record PendingEnrichment(Document document, Snippet card, String source, String jmixVersion,
                                     String contentHash) {
    }

    private List<Document> enrichDocuments(List<Document> documents) {
        if (!enricher.isEnabled() || documents.isEmpty()) {
            return documents;
        }
        String modelName = enricher.getModelName();

        List<Document> result = new ArrayList<>(documents.size());
        List<PendingEnrichment> pending = new ArrayList<>();

        for (Document document : documents) {
            Snippet card = Snippet.parse(document.getText());
            String source = getSourceFromDocument(document);
            String jmixVersion = (String) document.getMetadata().get("jmixVersion");
            String contentHash = (String) document.getMetadata().get("sourceHash");
            Optional<EnrichmentCache> cached = enrichmentCacheRepository
                    .find(getType(), source, jmixVersion, modelName)
                    .filter(entry -> Objects.equals(contentHash, entry.getContentHash()));
            if (cached.isPresent()) {
                result.add(withEnrichment(document, card,
                        new JavaApiEnricher.Enrichment(cached.get().getDescription(), cached.get().getExample())));
            } else {
                pending.add(new PendingEnrichment(document, card, source, jmixVersion, contentHash));
            }
        }

        if (!pending.isEmpty()) {
            log.info("Generating enrichment for {} documents (parallelism {})", pending.size(), enrichmentParallelism);
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(enrichmentParallelism, pending.size()));
            try {
                List<Future<JavaApiEnricher.Enrichment>> futures = new ArrayList<>(pending.size());
                for (PendingEnrichment item : pending) {
                    futures.add(executor.submit(() -> enricher.enrich(item.card().format())));
                }
                for (int i = 0; i < pending.size(); i++) {
                    PendingEnrichment item = pending.get(i);
                    JavaApiEnricher.Enrichment enrichment = getEnrichment(futures.get(i), item.source());
                    if (enrichment != null) {
                        // save on the caller thread: DataManager requires the caller's security context
                        enrichmentCacheRepository.save(getType(), item.source(), item.jmixVersion(), modelName,
                                item.contentHash(), enrichment.description(), enrichment.example());
                    }
                    result.add(withEnrichment(item.document(), item.card(), enrichment));
                    if ((i + 1) % 100 == 0) {
                        log.info("Enriched {}/{} documents", i + 1, pending.size());
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
        return result;
    }

    @Nullable
    private JavaApiEnricher.Enrichment getEnrichment(Future<JavaApiEnricher.Enrichment> future, String source) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Enrichment interrupted", e);
        } catch (ExecutionException e) {
            log.error("Enrichment failed for {}", source, e.getCause());
            return null;
        }
    }

    private Document withEnrichment(Document document, Snippet card, @Nullable JavaApiEnricher.Enrichment enrichment) {
        String text = JavaApiEnricher.assembleCard(card, enrichment);
        if (text.equals(document.getText())) {
            return document;
        }
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("enriched", "true");
        return createDocument(text, metadata);
    }
}
