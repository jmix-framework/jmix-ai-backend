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
    void calculatesSizeBreakdownWithStatisticsAndStackedBuckets() {
        when(repository.snippetTopicTokenSizes()).thenReturn(List.of(
                new Object[]{"data-access", 40},
                new Object[]{"data-access", 10},
                new Object[]{"security", 30},
                new Object[]{null, 20}));

        VectorStoreAnalyticsService.SnippetSizeBreakdown result = service.loadSnippetSizeBreakdown();

        assertThat(result.statistics()).isNotNull().satisfies(statistics -> {
            assertThat(statistics.count()).isEqualTo(4);
            assertThat(statistics.min()).isEqualTo(10);
            assertThat(statistics.median()).isEqualTo(25.0);
            assertThat(statistics.average()).isEqualTo(25.0);
            assertThat(statistics.max()).isEqualTo(40);
            assertThat(statistics.standardDeviation()).isCloseTo(Math.sqrt(125), within(0.0001));
        });
        assertThat(result.bucketStarts()).hasSize(20).startsWith(10, 12, 14).endsWith(48);

        assertThat(result.series()).hasSize(3);
        VectorStoreAnalyticsService.TopicSizeSeries dataAccess = result.series().getFirst();
        assertThat(dataAccess.topic()).isEqualTo("data-access");
        assertThat(dataAccess.count()).isEqualTo(2);
        assertThat(dataAccess.averageTokens()).isEqualTo(25.0);
        assertThat(dataAccess.bucketCounts().getFirst()).isEqualTo(1);
        assertThat(dataAccess.bucketCounts().get(15)).isEqualTo(1);

        VectorStoreAnalyticsService.TopicSizeSeries security = result.series().get(1);
        assertThat(security.topic()).isEqualTo("security");
        assertThat(security.averageTokens()).isEqualTo(30.0);
        assertThat(security.bucketCounts().get(10)).isEqualTo(1);

        VectorStoreAnalyticsService.TopicSizeSeries other = result.series().get(2);
        assertThat(other.topic()).isNull();
        assertThat(other.count()).isEqualTo(1);
        assertThat(other.bucketCounts().get(5)).isEqualTo(1);
    }

    @Test
    void rollsUpTopicsBeyondTopTwelveAndUnnamedTopicsIntoOther() {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int topic = 1; topic <= 12; topic++) {
            rows.add(new Object[]{"topic-%02d".formatted(topic), 100});
            rows.add(new Object[]{"topic-%02d".formatted(topic), 100});
        }
        rows.add(new Object[]{"topic-13", 100});
        rows.add(new Object[]{" ", 100});
        when(repository.snippetTopicTokenSizes()).thenReturn(rows);

        VectorStoreAnalyticsService.SnippetSizeBreakdown result = service.loadSnippetSizeBreakdown();

        assertThat(result.series()).hasSize(13);
        assertThat(result.series().subList(0, 12))
                .allSatisfy(series -> assertThat(series.count()).isEqualTo(2))
                .extracting(VectorStoreAnalyticsService.TopicSizeSeries::topic)
                .doesNotContain("topic-13", " ")
                .doesNotContainNull();
        VectorStoreAnalyticsService.TopicSizeSeries other = result.series().get(12);
        assertThat(other.topic()).isNull();
        assertThat(other.count()).isEqualTo(2);
    }

    @Test
    void breaksEqualTopicCountsByNameForStableTopSelection() {
        List<Object[]> rows = new java.util.ArrayList<>();
        // 13 topics with identical counts, supplied in reverse name order: without the name
        // tie-breaker the top-12 would follow the unspecified SQL row order
        for (int topic = 13; topic >= 1; topic--) {
            rows.add(new Object[]{"topic-%02d".formatted(topic), 100});
        }
        when(repository.snippetTopicTokenSizes()).thenReturn(rows);

        VectorStoreAnalyticsService.SnippetSizeBreakdown result = service.loadSnippetSizeBreakdown();

        assertThat(result.series()).hasSize(13);
        assertThat(result.series().subList(0, 12))
                .extracting(VectorStoreAnalyticsService.TopicSizeSeries::topic)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 12)
                        .mapToObj("topic-%02d"::formatted)
                        .toList());
        assertThat(result.series().get(12).topic()).isNull();
    }

    @Test
    void returnsEmptyBreakdownWhenCorpusIsEmpty() {
        when(repository.snippetTopicTokenSizes()).thenReturn(List.of());

        VectorStoreAnalyticsService.SnippetSizeBreakdown result = service.loadSnippetSizeBreakdown();

        assertThat(result.statistics()).isNull();
        assertThat(result.bucketStarts()).isEmpty();
        assertThat(result.series()).isEmpty();
    }

    @Test
    void groupsAndSortsTopicCoverageWithTotals() {
        when(repository.countSnippetTopicByVersion()).thenReturn(List.of(
                new Object[]{"data-access", "v2", 2},
                new Object[]{"data-access", "V3", 5},
                new Object[]{"security", null, 4},
                new Object[]{" ", "v3", 100}));

        VectorStoreAnalyticsService.TopicCoverageSummary result = service.loadTopicCoverage();

        assertThat(result.topics()).containsExactly(
                new VectorStoreAnalyticsService.TopicCoverage("data-access", 2, 5),
                new VectorStoreAnalyticsService.TopicCoverage("security", 4, 0));
        assertThat(result.maxCount()).isEqualTo(5);
        assertThat(result.totalV2()).isEqualTo(6);
        assertThat(result.totalV3()).isEqualTo(5);
    }

    @Test
    void breaksEqualCoverageTotalsByName() {
        when(repository.countSnippetTopicByVersion()).thenReturn(List.of(
                new Object[]{"security", "v2", 3},
                new Object[]{"data-access", "v2", 3}));

        assertThat(service.loadTopicCoverage().topics()).containsExactly(
                new VectorStoreAnalyticsService.TopicCoverage("data-access", 3, 0),
                new VectorStoreAnalyticsService.TopicCoverage("security", 3, 0));
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
