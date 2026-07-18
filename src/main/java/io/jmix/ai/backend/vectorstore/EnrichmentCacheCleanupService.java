package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.DeletionResult;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.Generation;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.GenerationKey;
import io.jmix.ai.backend.vectorstore.javaapi.JavaApiEnricher;
import io.jmix.ai.backend.vectorstore.snippets.SnippetizerEnricher;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EnrichmentCacheCleanupService {

    static final String JAVA_API = "javaapi-enriched";
    static final String DOCS_SNIPPETS = "docs-snippets";
    static final String UI_SAMPLES_SNIPPETS = "uisamples-snippets";

    private static final Comparator<Generation> NEWEST_FIRST = Comparator
            .comparing(Generation::latestCreatedDate,
                    Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()))
            .thenComparing(Generation::modelName);

    private final EnrichmentCacheRepository cacheRepository;
    private final JavaApiEnricher javaApiEnricher;
    private final SnippetizerEnricher snippetizerEnricher;

    public EnrichmentCacheCleanupService(EnrichmentCacheRepository cacheRepository,
                                         JavaApiEnricher javaApiEnricher,
                                         SnippetizerEnricher snippetizerEnricher) {
        this.cacheRepository = cacheRepository;
        this.javaApiEnricher = javaApiEnricher;
        this.snippetizerEnricher = snippetizerEnricher;
    }

    public CleanupResult cleanup() {
        String snippetModelName = snippetizerEnricher.getModelKey();
        Map<String, String> activeModelNames = Map.of(
                JAVA_API, javaApiEnricher.getModelKey(),
                DOCS_SNIPPETS, snippetModelName,
                UI_SAMPLES_SNIPPETS, snippetModelName);
        CleanupPlan plan = planCleanup(cacheRepository.findGenerations(), activeModelNames);
        DeletionResult deletion = cacheRepository.deleteGenerations(plan.obsoleteGenerations());
        return new CleanupResult(deletion.entries(), deletion.generations(), plan.skippedScopes());
    }

    static CleanupPlan planCleanup(List<Generation> generations, Map<String, String> activeModelNames) {
        Map<Scope, List<Generation>> byScope = generations.stream()
                .collect(Collectors.groupingBy(
                        generation -> new Scope(generation.type(), generation.jmixVersion())));
        Set<GenerationKey> obsolete = new LinkedHashSet<>();
        int skippedScopes = 0;

        for (Map.Entry<Scope, List<Generation>> entry : byScope.entrySet()) {
            String activeModelName = activeModelNames.get(entry.getKey().type());
            if (activeModelName == null) {
                skippedScopes++;
                continue;
            }

            List<Generation> ordered = new ArrayList<>(entry.getValue());
            ordered.sort(NEWEST_FIRST);
            String previousModelName = ordered.stream()
                    .map(Generation::modelName)
                    .filter(modelName -> !modelName.equals(activeModelName))
                    .findFirst()
                    .orElse(null);

            ordered.stream()
                    .filter(generation -> !generation.modelName().equals(activeModelName))
                    .filter(generation -> !generation.modelName().equals(previousModelName))
                    .map(Generation::key)
                    .forEach(obsolete::add);
        }
        return new CleanupPlan(obsolete, skippedScopes);
    }

    record Scope(String type, String jmixVersion) {
    }

    record CleanupPlan(Set<GenerationKey> obsoleteGenerations, int skippedScopes) {
    }

    public record CleanupResult(int deletedEntries, int deletedGenerations, int skippedScopes) {
    }
}
