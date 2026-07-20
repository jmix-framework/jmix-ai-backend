package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.vectorstore.CorpusType;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Parallel corpus to {@link JavaApiIngester}: the same API cards where an LLM
 * ({@link JavaApiEnricher}) additionally generates a description and a usage example.
 * <p>
 * Cards of new or changed pages (they are the only ones that reach {@link #prepareCards})
 * are enriched in parallel; generated content is cached in {@link EnrichmentCache} by source
 * hash, so re-ingestion of unchanged pages costs no LLM calls. Cache lookups and saves stay
 * on the caller thread, which has the Jmix security context required by DataManager.
 */
@Component
public class JavaApiEnrichedIngester extends JavaApiIngester {

    private static final Logger log = LoggerFactory.getLogger(JavaApiEnrichedIngester.class);

    private final int enrichmentParallelism;
    private final JavaApiEnricher enricher;
    private final EnrichmentCacheRepository enrichmentCacheRepository;
    private final ExecutorService enrichmentExecutor;

    public JavaApiEnrichedIngester(
            @Value("${javaapi.v2.base-url:}") String v2BaseUrl,
            @Value("${javaapi.v3.base-url:}") String v3BaseUrl,
            @Value("${javaapi.class-list-page}") String classListPage,
            @Value("${javaapi.whitelist}") String whitelist,
            @Value("${javaapi.blacklist:}") String blacklist,
            @Value("${javaapi.limit}") int limit,
            @Value("${javaapi.enrichment.parallelism}") int enrichmentParallelism,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            @Qualifier("ingestionRestTemplate") RestTemplate restTemplate,
            JavaApiEnricher enricher,
            EnrichmentCacheRepository enrichmentCacheRepository) {
        super(v2BaseUrl, v3BaseUrl, classListPage, whitelist, blacklist, limit,
                vectorStore, timeSource, vectorStoreRepository, restTemplate);
        this.enrichmentParallelism = Math.max(1, enrichmentParallelism);
        this.enricher = enricher;
        this.enrichmentCacheRepository = enrichmentCacheRepository;
        this.enrichmentExecutor = Executors.newFixedThreadPool(this.enrichmentParallelism);
    }

    @Override
    public String getType() {
        return CorpusType.JAVA_API_ENRICHED;
    }

    @Override
    protected String currentGenerationKey() {
        return super.currentGenerationKey() + ":" + enricher.getModelKey();
    }

    @Override
    protected List<Document> prepareCards(List<Document> documents) {
        return enrichDocuments(documents);
    }

    /** Failed enrichment stays unstamped so the next update retries it. */
    @Override
    protected boolean shouldStampGenerationKey(Document document) {
        return "true".equals(document.getMetadata().get("enriched"));
    }

    private record PendingEnrichment(Document document, Snippet card, String source, String jmixVersion,
                                     String contentHash) {
    }

    private List<Document> enrichDocuments(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        String modelName = enricher.getModelKey();

        List<Document> result = new ArrayList<>(documents.size());
        List<PendingEnrichment> pending = new ArrayList<>();

        for (Document document : documents) {
            Snippet card = Snippet.parse(document.getText());
            String source = getSourceFromDocument(document);
            String jmixVersion = (String) document.getMetadata().get("jmixVersion");
            String contentHash = (String) document.getMetadata().get("sourceHash");
            Optional<JavaApiEnricher.Enrichment> cached = enrichmentCacheRepository
                    .find(getType(), source, jmixVersion, modelName)
                    .filter(entry -> Objects.equals(contentHash, entry.getContentHash()))
                    .map(EnrichmentCache::getContent)
                    .map(JavaApiEnricher::fromCacheJson);
            if (cached.isPresent()) {
                result.add(withEnrichment(document, card, cached.get()));
            } else {
                pending.add(new PendingEnrichment(document, card, source, jmixVersion, contentHash));
            }
        }

        if (!pending.isEmpty()) {
            log.info("Generating enrichment for {} documents (parallelism {})", pending.size(), enrichmentParallelism);
            CompletionService<JavaApiEnricher.Enrichment> completed =
                    new ExecutorCompletionService<>(enrichmentExecutor);
            Map<Future<JavaApiEnricher.Enrichment>, PendingEnrichment> inFlight = new HashMap<>();
            int next = 0;
            int completedCount = 0;
            try {
                while (next < pending.size() && inFlight.size() < enrichmentParallelism) {
                    PendingEnrichment item = pending.get(next++);
                    inFlight.put(completed.submit(() -> enricher.enrich(item.card().format())), item);
                }

                while (!inFlight.isEmpty()) {
                    Future<JavaApiEnricher.Enrichment> future = completed.take();
                    PendingEnrichment item = inFlight.remove(future);
                    JavaApiEnricher.Enrichment enrichment = getEnrichment(future, item.source());
                    if (enrichment != null) {
                        // save on the caller thread: DataManager requires the caller's security context
                        enrichmentCacheRepository.save(getType(), item.source(), item.jmixVersion(), modelName,
                                item.contentHash(), JavaApiEnricher.toCacheJson(enrichment));
                    }
                    result.add(withEnrichment(item.document(), item.card(), enrichment));
                    completedCount++;
                    if (completedCount % 100 == 0) {
                        log.info("Enriched {}/{} documents", completedCount, pending.size());
                    }
                    if (next < pending.size()) {
                        PendingEnrichment nextItem = pending.get(next++);
                        inFlight.put(completed.submit(() -> enricher.enrich(nextItem.card().format())), nextItem);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Enrichment interrupted", e);
            } finally {
                inFlight.keySet().forEach(future -> future.cancel(true));
            }
        }
        return result;
    }

    @PreDestroy
    void shutdownEnrichmentExecutor() {
        enrichmentExecutor.shutdown();
        try {
            if (!enrichmentExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                enrichmentExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            enrichmentExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
        if (enrichment == null) {
            return document;
        }
        String text = JavaApiEnricher.assembleCard(card, enrichment);
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("enriched", "true");
        return createDocument(text, metadata);
    }
}
