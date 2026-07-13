package io.jmix.ai.backend.vectorstore;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VectorStoreAnalyticsService {

    private static final int TOP_TOKEN_TOPICS = 20;
    private static final int TOP_COVERAGE_TOPICS = 24;
    private static final int HISTOGRAM_BUCKETS = 20;

    private final VectorStoreRepository vectorStoreRepository;

    public VectorStoreAnalyticsService(VectorStoreRepository vectorStoreRepository) {
        this.vectorStoreRepository = vectorStoreRepository;
    }

    public record TopicTokenAverage(String topic, int tokens) {
    }

    public record TokenStatistics(int count, int min, double median, double average, int max,
                                  double standardDeviation) {
    }

    public record HistogramBucket(int start, int snippets) {
    }

    public record TokenDistribution(@Nullable TokenStatistics statistics, List<HistogramBucket> buckets) {
    }

    public record TopicCoverage(String topic, int v2, int v3) {
    }

    public record TopicCoverageSummary(List<TopicCoverage> topics, int maxCount) {
    }

    public record CorpusCoverage(String corpus, int v2, int v3, int shared) {
    }

    public List<TopicTokenAverage> loadTopTokenAverages() {
        return vectorStoreRepository.avgSnippetTokensByTopic().stream()
                .filter(row -> row[0] != null && !((String) row[0]).isBlank())
                .map(row -> new TopicTokenAverage((String) row[0], ((Number) row[1]).intValue()))
                .sorted((first, second) -> Integer.compare(second.tokens(), first.tokens()))
                .limit(TOP_TOKEN_TOPICS)
                .toList();
    }

    public TokenDistribution loadTokenDistribution() {
        List<Integer> sorted = vectorStoreRepository.snippetTokenSizes().stream().sorted().toList();
        if (sorted.isEmpty()) {
            return new TokenDistribution(null, List.of());
        }

        int count = sorted.size();
        int min = sorted.get(0);
        int max = sorted.get(count - 1);
        double average = sorted.stream().mapToInt(Integer::intValue).average().orElse(0);
        double median = count % 2 == 1
                ? sorted.get(count / 2)
                : (sorted.get(count / 2 - 1) + sorted.get(count / 2)) / 2.0;
        double standardDeviation = Math.sqrt(sorted.stream()
                .mapToDouble(size -> (size - average) * (size - average))
                .sum() / count);
        TokenStatistics statistics = new TokenStatistics(
                count, min, median, average, max, standardDeviation);

        int range = Math.max(1, max - min);
        int width = (int) Math.ceil(range / (double) HISTOGRAM_BUCKETS);
        int[] counts = new int[HISTOGRAM_BUCKETS];
        for (int size : sorted) {
            int index = Math.min(HISTOGRAM_BUCKETS - 1, (size - min) / width);
            counts[index]++;
        }
        List<HistogramBucket> buckets = new ArrayList<>(HISTOGRAM_BUCKETS);
        for (int index = 0; index < HISTOGRAM_BUCKETS; index++) {
            buckets.add(new HistogramBucket(min + index * width, counts[index]));
        }
        return new TokenDistribution(statistics, List.copyOf(buckets));
    }

    public TopicCoverageSummary loadTopTopicCoverage() {
        Map<String, int[]> countsByTopic = new LinkedHashMap<>();
        for (Object[] row : vectorStoreRepository.countSnippetTopicByVersion()) {
            String topic = (String) row[0];
            if (topic == null || topic.isBlank()) {
                continue;
            }
            String version = (String) row[1];
            int count = ((Number) row[2]).intValue();
            int[] counts = countsByTopic.computeIfAbsent(topic, ignored -> new int[2]);
            if ("v3".equalsIgnoreCase(version)) {
                counts[1] += count;
            } else {
                counts[0] += count;
            }
        }

        List<TopicCoverage> topics = countsByTopic.entrySet().stream()
                .map(entry -> new TopicCoverage(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted((first, second) -> Integer.compare(total(second), total(first)))
                .limit(TOP_COVERAGE_TOPICS)
                .toList();
        int maxCount = topics.stream()
                .mapToInt(topic -> Math.max(topic.v2(), topic.v3()))
                .max()
                .orElse(1);
        return new TopicCoverageSummary(topics, maxCount);
    }

    public List<CorpusCoverage> loadCorpusCoverage() {
        Map<String, int[]> countsByType = new LinkedHashMap<>();
        for (Object[] row : vectorStoreRepository.countByTypeAndVersion()) {
            String type = (String) row[0];
            if (type == null) {
                continue;
            }
            String version = (String) row[1];
            int count = ((Number) row[2]).intValue();
            int[] counts = countsByType.computeIfAbsent(type, ignored -> new int[3]);
            if ("v2".equalsIgnoreCase(version)) {
                counts[0] += count;
            } else if ("v3".equalsIgnoreCase(version)) {
                counts[1] += count;
            } else {
                counts[2] += count;
            }
        }

        List<CorpusCoverage> coverage = new ArrayList<>(countsByType.size());
        countsByType.forEach((type, counts) -> coverage.add(
                new CorpusCoverage(type, counts[0], counts[1], counts[2])));
        return List.copyOf(coverage);
    }

    private static int total(TopicCoverage coverage) {
        return coverage.v2() + coverage.v3();
    }
}
