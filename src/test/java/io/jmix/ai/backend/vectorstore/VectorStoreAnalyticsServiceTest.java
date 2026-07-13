package io.jmix.ai.backend.vectorstore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorStoreAnalyticsServiceTest {

    private final VectorStoreRepository repository = mock(VectorStoreRepository.class);
    private final VectorStoreAnalyticsService service = new VectorStoreAnalyticsService(repository);

    @Test
    void calculatesTokenStatisticsAndFixedWidthHistogram() {
        when(repository.snippetTokenSizes()).thenReturn(List.of(40, 10, 30, 20));

        VectorStoreAnalyticsService.TokenDistribution result = service.loadTokenDistribution();

        assertThat(result.statistics()).isNotNull().satisfies(statistics -> {
            assertThat(statistics.count()).isEqualTo(4);
            assertThat(statistics.min()).isEqualTo(10);
            assertThat(statistics.median()).isEqualTo(25.0);
            assertThat(statistics.average()).isEqualTo(25.0);
            assertThat(statistics.max()).isEqualTo(40);
            assertThat(statistics.standardDeviation()).isCloseTo(Math.sqrt(125), within(0.0001));
        });
        assertThat(result.buckets()).hasSize(20);
        assertThat(result.buckets().get(0))
                .isEqualTo(new VectorStoreAnalyticsService.HistogramBucket(10, 1));
        assertThat(result.buckets().get(5))
                .isEqualTo(new VectorStoreAnalyticsService.HistogramBucket(20, 1));
        assertThat(result.buckets().get(10))
                .isEqualTo(new VectorStoreAnalyticsService.HistogramBucket(30, 1));
        assertThat(result.buckets().get(15))
                .isEqualTo(new VectorStoreAnalyticsService.HistogramBucket(40, 1));
    }

    @Test
    void returnsEmptyDistributionWhenCorpusIsEmpty() {
        when(repository.snippetTokenSizes()).thenReturn(List.of());

        VectorStoreAnalyticsService.TokenDistribution result = service.loadTokenDistribution();

        assertThat(result.statistics()).isNull();
        assertThat(result.buckets()).isEmpty();
    }

    @Test
    void sortsTokenAveragesAndSkipsUnnamedTopics() {
        when(repository.avgSnippetTokensByTopic()).thenReturn(List.of(
                new Object[]{"data-access", 120, 2},
                new Object[]{null, 900, 1},
                new Object[]{"", 800, 1},
                new Object[]{"security", 240, 3}));

        assertThat(service.loadTopTokenAverages())
                .containsExactly(
                        new VectorStoreAnalyticsService.TopicTokenAverage("security", 240),
                        new VectorStoreAnalyticsService.TopicTokenAverage("data-access", 120));
    }

    @Test
    void groupsAndSortsTopicCoverage() {
        when(repository.countSnippetTopicByVersion()).thenReturn(List.of(
                new Object[]{"data-access", "v2", 2},
                new Object[]{"data-access", "V3", 5},
                new Object[]{"security", null, 4},
                new Object[]{" ", "v3", 100}));

        VectorStoreAnalyticsService.TopicCoverageSummary result = service.loadTopTopicCoverage();

        assertThat(result.topics()).containsExactly(
                new VectorStoreAnalyticsService.TopicCoverage("data-access", 2, 5),
                new VectorStoreAnalyticsService.TopicCoverage("security", 4, 0));
        assertThat(result.maxCount()).isEqualTo(5);
    }

    @Test
    void groupsVersionedAndSharedCorpusCoverage() {
        when(repository.countByTypeAndVersion()).thenReturn(List.of(
                new Object[]{"docs", "v2", 100},
                new Object[]{"uisamples", "v3", 25},
                new Object[]{null, "v2", 100},
                new Object[]{"docs-snippets", "v2", 3},
                new Object[]{"docs-snippets", "V2", 2},
                new Object[]{"docs-snippets", "v3", 4},
                new Object[]{"trainings", null, 7}));

        assertThat(service.loadCorpusCoverage()).containsExactly(
                new VectorStoreAnalyticsService.CorpusCoverage("docs", 100, 0, 0),
                new VectorStoreAnalyticsService.CorpusCoverage("uisamples", 0, 25, 0),
                new VectorStoreAnalyticsService.CorpusCoverage("docs-snippets", 5, 4, 0),
                new VectorStoreAnalyticsService.CorpusCoverage("trainings", 0, 0, 7));
    }
}
