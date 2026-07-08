package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JavaApiIngesterTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private TimeSource timeSource;
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    @Mock
    private JavaApiEnricher enricher;
    @Mock
    private EnrichmentCacheRepository enrichmentCacheRepository;

    private JavaApiIngester ingester;

    private static final Snippet CARD = new Snippet(
            "Interface DataManager (io.jmix.core)",
            "Same as UnconstrainedDataManager but performs authorization.",
            "java",
            "public interface DataManager extends UnconstrainedDataManager",
            "https://docs.jmix.io/api/2.8/io/jmix/core/DataManager.html");

    @BeforeEach
    void setUp() {
        ingester = new JavaApiIngester(
                "https://docs.jmix.io/api/2.8", "allclasses-index.html", "core", "/impl/,/antlr2/", 0, 4,
                vectorStore, timeSource, vectorStoreRepository, enricher, enrichmentCacheRepository);
    }

    private Document cardDocument() {
        return new Document("1", CARD.format(), Map.of(
                "type", "javaapi",
                "source", "io/jmix/core/DataManager.html",
                "sourceHash", "hash1"));
    }

    @Test
    void splitToChunks_SkipsEnrichmentWhenDisabled() {
        when(enricher.isEnabled()).thenReturn(false);

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo(CARD.format());
        assertThat(chunks.get(0).getMetadata()).doesNotContainKey("enriched");
        verify(enricher, never()).enrich(anyString());
    }

    @Test
    void splitToChunks_UsesCachedEnrichmentWithMatchingHash() {
        when(enricher.isEnabled()).thenReturn(true);
        when(enricher.getModelKey()).thenReturn("test-model");
        EnrichmentCache cached = new EnrichmentCache();
        cached.setContentHash("hash1");
        cached.setDescription("Cached description.");
        cached.setExample("DataManager dm;");
        when(enrichmentCacheRepository.find("javaapi", "io/jmix/core/DataManager.html", "test-model"))
                .thenReturn(Optional.of(cached));

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText())
                .contains("DESCRIPTION: Cached description.")
                .contains("// Usage example:\nDataManager dm;");
        assertThat(chunks.get(0).getMetadata()).containsEntry("enriched", "true");
        verify(enricher, never()).enrich(anyString());
        verify(enrichmentCacheRepository, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void splitToChunks_GeneratesAndCachesWhenHashDiffers() {
        when(enricher.isEnabled()).thenReturn(true);
        when(enricher.getModelKey()).thenReturn("test-model");
        EnrichmentCache stale = new EnrichmentCache();
        stale.setContentHash("old-hash");
        when(enrichmentCacheRepository.find(any(), any(), any())).thenReturn(Optional.of(stale));
        when(enricher.enrich(anyString()))
                .thenReturn(new JavaApiEnricher.Enrichment("Generated description.", "dm.unconstrained();"));

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText())
                .contains("DESCRIPTION: Generated description.")
                .contains("dm.unconstrained();");
        verify(enricher).enrich(CARD.format());
        verify(enrichmentCacheRepository).save("javaapi", "io/jmix/core/DataManager.html", "test-model",
                "hash1", "Generated description.", "dm.unconstrained();");
    }

    @Test
    void splitToChunks_FallsBackToDeterministicCardOnGenerationFailure() {
        when(enricher.isEnabled()).thenReturn(true);
        when(enricher.getModelKey()).thenReturn("test-model");
        when(enrichmentCacheRepository.find(any(), any(), any())).thenReturn(Optional.empty());
        when(enricher.enrich(anyString())).thenReturn(null);

        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo(CARD.format());
        assertThat(chunks.get(0).getMetadata()).doesNotContainKey("enriched");
        verify(enrichmentCacheRepository, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void splitToChunks_EnrichesAllDocumentsInParallel() {
        when(enricher.isEnabled()).thenReturn(true);
        when(enricher.getModelKey()).thenReturn("test-model");
        when(enrichmentCacheRepository.find(any(), any(), any())).thenReturn(Optional.empty());
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
    void blacklistExcludesInternalPackages() {
        assertThat(ingester.isAllowedSource("io/jmix/core/DataManager.html")).isTrue();
        assertThat(ingester.isAllowedSource("io/jmix/core/impl/DataManagerImpl.html")).isFalse();
        assertThat(ingester.isAllowedSource("io/jmix/data/impl/jpql/antlr2/JPA2Parser.html")).isFalse();
        assertThat(ingester.isAllowedSource("io/jmix/reports/ReportRunner.html")).isFalse();
    }
}
