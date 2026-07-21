package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.vectorstore.CorpusType;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JavaApiEnrichedIngesterTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private TimeSource timeSource;
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private JavaApiEnricher enricher;
    @Mock
    private EnrichmentCacheRepository enrichmentCacheRepository;

    private JavaApiEnrichedIngester ingester;

    private static final Snippet CARD = new Snippet(
            "Interface DataManager (io.jmix.core)",
            "Same as UnconstrainedDataManager but performs authorization.",
            "java",
            "public interface DataManager extends UnconstrainedDataManager",
            "https://docs.jmix.io/api/2.8/io/jmix/core/DataManager.html");

    @BeforeEach
    void setUp() {
        ingester = new JavaApiEnrichedIngester(
                "https://docs.jmix.io/api/2.8", "", "allclasses-index.html", "core", "/impl/,/antlr2/", 0, 4,
                vectorStore, timeSource, vectorStoreRepository, restTemplate, enricher, enrichmentCacheRepository);
    }

    @AfterEach
    void tearDown() {
        ingester.shutdownEnrichmentExecutor();
    }

    private Document cardDocument() {
        return new Document("1", CARD.format(), Map.of(
                "type", "javaapi-enriched",
                "source", "io/jmix/core/DataManager.html",
                "sourceHash", "hash1",
                "jmixVersion", "v2"));
    }

    private Document cardDocument(String id) {
        return new Document(id, CARD.format(), cardDocument().getMetadata());
    }

    @Test
    void splitToChunks_UsesCachedEnrichmentWithMatchingHash() {
        when(enricher.getModelKey()).thenReturn("test-model");
        EnrichmentCache cached = new EnrichmentCache();
        cached.setContentHash("hash1");
        cached.setContent(JavaApiEnricher.toCacheJson(
                new JavaApiEnricher.Enrichment("Cached description.", "DataManager dm;")));
        when(enrichmentCacheRepository.find(CorpusType.JAVA_API_ENRICHED, "io/jmix/core/DataManager.html",
                JmixVersion.V2, "test-model"))
                .thenReturn(Optional.of(cached));

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText())
                .contains("DESCRIPTION: Cached description.")
                .contains("// Usage example:\nDataManager dm;");
        assertThat(chunks.getFirst().getMetadata()).containsEntry("enriched", "true");
        verify(enricher, never()).enrich(anyString());
        verify(enrichmentCacheRepository, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void splitToChunks_GeneratesAndCachesWhenHashDiffers() {
        when(enricher.getModelKey()).thenReturn("test-model");
        EnrichmentCache stale = new EnrichmentCache();
        stale.setContentHash("old-hash");
        when(enrichmentCacheRepository.find(any(), any(), any(), any())).thenReturn(Optional.of(stale));
        when(enricher.enrich(anyString()))
                .thenReturn(new JavaApiEnricher.Enrichment("Generated description.", "dm.unconstrained();"));

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText())
                .contains("DESCRIPTION: Generated description.")
                .contains("dm.unconstrained();");
        verify(enricher).enrich(CARD.format());
        verify(enrichmentCacheRepository).save(CorpusType.JAVA_API_ENRICHED, "io/jmix/core/DataManager.html",
                JmixVersion.V2, "test-model", "hash1",
                JavaApiEnricher.toCacheJson(new JavaApiEnricher.Enrichment("Generated description.", "dm.unconstrained();")));
    }

    @Test
    void splitToChunks_StampsSuccessfulEnrichmentWhenTextIsUnchanged() {
        when(enricher.getModelKey()).thenReturn("test-model");
        when(enrichmentCacheRepository.find(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(enricher.enrich(anyString()))
                .thenReturn(new JavaApiEnricher.Enrichment(CARD.description(), ""));

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getText()).isEqualTo(CARD.format());
            assertThat(chunk.getMetadata())
                    .containsEntry("enriched", "true")
                    .containsEntry("generationKey", "card-v3:test-model");
        });
    }

    @Test
    void splitToChunks_FallsBackToDeterministicCardOnGenerationFailure() {
        when(enricher.getModelKey()).thenReturn("test-model");
        when(enrichmentCacheRepository.find(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(enricher.enrich(anyString())).thenReturn(null);

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText()).isEqualTo(CARD.format());
        assertThat(chunks.getFirst().getMetadata())
                .doesNotContainKey("enriched")
                .doesNotContainKey("generationKey");
        verify(enrichmentCacheRepository, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void splitToChunks_EnrichesAllDocumentsInParallel() {
        when(enricher.getModelKey()).thenReturn("test-model");
        when(enrichmentCacheRepository.find(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(enricher.enrich(anyString()))
                .thenAnswer(inv -> new JavaApiEnricher.Enrichment("Generated.", ""));

        List<Document> documents = List.of(cardDocument(), cardDocument(), cardDocument(), cardDocument());

        List<Document> chunks = ingester.splitToChunks(documents);

        assertThat(chunks).hasSize(4);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.getText()).contains("DESCRIPTION: Generated."));
        verify(enricher, times(4)).enrich(anyString());
        verify(enrichmentCacheRepository, times(4)).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void splitToChunks_CancelsBoundedInFlightWorkWhenInterrupted() throws Exception {
        when(enricher.getModelKey()).thenReturn("test-model");
        when(enrichmentCacheRepository.find(any(), any(), any(), any())).thenReturn(Optional.empty());
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch interrupted = new CountDownLatch(4);
        when(enricher.enrich(anyString())).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return null;
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return null;
            }
        });
        List<Document> documents = List.of(
                cardDocument("1"), cardDocument("2"), cardDocument("3"), cardDocument("4"), cardDocument("5"));
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            Future<List<Document>> update = caller.submit(() -> ingester.splitToChunks(documents));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            verify(enricher, times(4)).enrich(anyString());

            update.cancel(true);

            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(enricher, times(4)).enrich(anyString());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void getType_IsEnrichedCorpus() {
        assertThat(ingester.getType()).isEqualTo("javaapi-enriched");
    }
}
