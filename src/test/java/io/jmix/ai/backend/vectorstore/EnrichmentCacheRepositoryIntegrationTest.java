package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.DeletionResult;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.Generation;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository.GenerationKey;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import test_support.AuthenticatedAsAdmin;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AuthenticatedAsAdmin.class)
class EnrichmentCacheRepositoryIntegrationTest {

    @Autowired
    private DataManager dataManager;
    @Autowired
    private EnrichmentCacheRepository repository;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clearCache() {
        new JdbcTemplate(dataSource).execute("delete from ENRICHMENT_CACHE");
    }

    @Test
    void findsGenerationRecencyAndDeletesOnlyRequestedGeneration() {
        dataManager.save(
                cache("source-a", "v2", "current"),
                cache("source-b", "v2", "current"),
                cache("source-a", "v2", "old"),
                cache("source-a", null, "shared-old"));

        List<EnrichmentCache> stored = dataManager.load(EnrichmentCache.class).all().list();

        List<Generation> generations = repository.findGenerations();

        assertThat(generations).containsExactlyInAnyOrder(
                new Generation("docs-snippets", "v2", "current", stored.stream()
                        .filter(cache -> cache.getModelName().equals("current"))
                        .map(EnrichmentCache::getCreatedDate)
                        .max(Comparator.naturalOrder())
                        .orElseThrow()),
                generation(stored, "old"),
                generation(stored, "shared-old"));

        DeletionResult deletion = repository.deleteGenerations(Set.of(
                new GenerationKey("docs-snippets", "v2", "old"),
                new GenerationKey("docs-snippets", null, "shared-old")));

        assertThat(deletion).isEqualTo(new DeletionResult(2, 2));
        assertThat(dataManager.load(EnrichmentCache.class).all().list())
                .extracting(EnrichmentCache::getModelName)
                .containsOnly("current");
    }

    private Generation generation(List<EnrichmentCache> stored, String modelName) {
        EnrichmentCache cache = stored.stream()
                .filter(item -> item.getModelName().equals(modelName))
                .findFirst()
                .orElseThrow();
        return new Generation(cache.getType(), cache.getJmixVersion(), modelName, cache.getCreatedDate());
    }

    private EnrichmentCache cache(String source, String jmixVersion, String modelName) {
        EnrichmentCache cache = dataManager.create(EnrichmentCache.class);
        cache.setType("docs-snippets");
        cache.setSource(source);
        cache.setJmixVersion(jmixVersion);
        cache.setModelName(modelName);
        cache.setContentHash("hash");
        cache.setDescription("description");
        cache.setExample("");
        return cache;
    }
}
