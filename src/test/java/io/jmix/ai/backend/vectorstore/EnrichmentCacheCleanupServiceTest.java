package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.vectorstore.EnrichmentCacheCleanupService.CleanupPlan;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheCleanupService.CleanupResult;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.DeletionResult;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.Generation;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.GenerationKey;
import io.jmix.ai.backend.vectorstore.javaapi.JavaApiEnricher;
import io.jmix.ai.backend.vectorstore.snippets.SnippetizerEnricher;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrichmentCacheCleanupServiceTest {

    private static final OffsetDateTime OLDEST = OffsetDateTime.parse("2026-07-11T10:00:00Z");
    private static final OffsetDateTime MIDDLE = OffsetDateTime.parse("2026-07-12T10:00:00Z");
    private static final OffsetDateTime NEWEST = OffsetDateTime.parse("2026-07-13T10:00:00Z");

    @Test
    void planCleanup_KeepsActiveRolledBackGenerationAndNewestOtherGeneration() {
        List<Generation> generations = List.of(
                generation("docs-snippets", "v2", "prompt-p1", OLDEST),
                generation("docs-snippets", "v2", "prompt-p4", MIDDLE),
                generation("docs-snippets", "v2", "prompt-p5", NEWEST));

        CleanupPlan plan = EnrichmentCacheCleanupService.planCleanup(
                generations, Map.of("docs-snippets", "prompt-p1"));

        assertThat(plan.obsoleteGenerations()).containsExactly(
                key("docs-snippets", "v2", "prompt-p4"));
        assertThat(plan.skippedScopes()).isZero();
    }

    @Test
    void planCleanup_TreatsTypeAndVersionAsIndependentScopes() {
        List<Generation> generations = List.of(
                generation("docs-snippets", "v2", "docs-current", MIDDLE),
                generation("docs-snippets", "v2", "docs-previous", NEWEST),
                generation("docs-snippets", "v2", "docs-old", null),
                generation("docs-snippets", null, "docs-current", OLDEST),
                generation("docs-snippets", null, "docs-null-previous", NEWEST),
                generation("docs-snippets", null, "docs-null-old", null),
                generation("uisamples-snippets", "v2", "ui-current", OLDEST),
                generation("uisamples-snippets", "v2", "ui-previous", MIDDLE),
                generation("uisamples-snippets", "v2", "ui-old", null));

        CleanupPlan plan = EnrichmentCacheCleanupService.planCleanup(generations, Map.of(
                "docs-snippets", "docs-current",
                "uisamples-snippets", "ui-current"));

        assertThat(plan.obsoleteGenerations()).containsExactlyInAnyOrder(
                key("docs-snippets", "v2", "docs-old"),
                key("docs-snippets", null, "docs-null-old"),
                key("uisamples-snippets", "v2", "ui-old"));
        assertThat(plan.skippedScopes()).isZero();
    }

    @Test
    void planCleanup_WhenActiveGenerationDoesNotExist_KeepsNewestExistingGeneration() {
        List<Generation> generations = List.of(
                generation("javaapi", "v3", "prompt-p3", OLDEST),
                generation("javaapi", "v3", "prompt-p4", MIDDLE),
                generation("javaapi", "v3", "prompt-p5", NEWEST));

        CleanupPlan plan = EnrichmentCacheCleanupService.planCleanup(
                generations, Map.of("javaapi", "prompt-p6"));

        assertThat(plan.obsoleteGenerations()).containsExactlyInAnyOrder(
                key("javaapi", "v3", "prompt-p3"),
                key("javaapi", "v3", "prompt-p4"));
        assertThat(plan.obsoleteGenerations()).doesNotContain(
                key("javaapi", "v3", "prompt-p5"));
        assertThat(plan.skippedScopes()).isZero();
    }

    @Test
    void planCleanup_SkipsUnknownCacheType() {
        List<Generation> generations = List.of(
                generation("legacy", "v2", "prompt-p1", OLDEST),
                generation("legacy", "v2", "prompt-p2", NEWEST));

        CleanupPlan plan = EnrichmentCacheCleanupService.planCleanup(generations, Map.of());

        assertThat(plan.obsoleteGenerations()).isEmpty();
        assertThat(plan.skippedScopes()).isEqualTo(1);
    }

    @Test
    void cleanup_UsesCurrentModelKeysForEveryKnownTypeAndReturnsDeletionCounts() {
        EnrichmentCacheRepository repository = mock(EnrichmentCacheRepository.class);
        JavaApiEnricher javaApiEnricher = mock(JavaApiEnricher.class);
        SnippetizerEnricher snippetizerEnricher = mock(SnippetizerEnricher.class);
        when(javaApiEnricher.getModelKey()).thenReturn("java-current");
        when(snippetizerEnricher.getModelKey()).thenReturn("snippet-current");
        when(repository.findGenerations()).thenReturn(List.of(
                generation("javaapi-enriched", "v2", "java-current", OLDEST),
                generation("javaapi-enriched", "v2", "java-previous", NEWEST),
                generation("javaapi-enriched", "v2", "java-old", null),
                generation("docs-snippets", "v2", "snippet-current", OLDEST),
                generation("docs-snippets", "v2", "docs-previous", NEWEST),
                generation("docs-snippets", "v2", "docs-old", null),
                generation("uisamples-snippets", "v3", "snippet-current", OLDEST),
                generation("uisamples-snippets", "v3", "ui-previous", NEWEST),
                generation("uisamples-snippets", "v3", "ui-old", null)));
        Set<GenerationKey> obsolete = Set.of(
                key("javaapi-enriched", "v2", "java-old"),
                key("docs-snippets", "v2", "docs-old"),
                key("uisamples-snippets", "v3", "ui-old"));
        when(repository.deleteGenerations(obsolete)).thenReturn(new DeletionResult(17, 3));
        EnrichmentCacheCleanupService service = new EnrichmentCacheCleanupService(
                repository, javaApiEnricher, snippetizerEnricher);

        CleanupResult result = service.cleanup();

        assertThat(result).isEqualTo(new CleanupResult(17, 3, 0));
        verify(javaApiEnricher).getModelKey();
        verify(snippetizerEnricher).getModelKey();
        verify(repository).deleteGenerations(obsolete);
    }

    private static Generation generation(String type, String version, String modelName,
                                         OffsetDateTime latestCreatedDate) {
        return new Generation(type, version, modelName, latestCreatedDate);
    }

    private static GenerationKey key(String type, String version, String modelName) {
        return new GenerationKey(type, version, modelName);
    }
}
