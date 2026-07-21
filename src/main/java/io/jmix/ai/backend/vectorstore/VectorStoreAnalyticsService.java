package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.JmixVersion;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VectorStoreAnalyticsService {

    private static final int HISTOGRAM_BUCKETS = 20;
    private static final int TOP_SIZE_TOPICS = 12;

    private final VectorStoreRepository vectorStoreRepository;

    public VectorStoreAnalyticsService(VectorStoreRepository vectorStoreRepository) {
        this.vectorStoreRepository = vectorStoreRepository;
    }

    public record TokenStatistics(int count, int min, double median, double average, int max,
                                  double standardDeviation) {
    }

    /** One stacked-histogram series; a null topic aggregates everything beyond the top topics. */
    public record TopicSizeSeries(@Nullable String topic, int count, double averageTokens,
                                  List<Integer> bucketCounts) {
    }

    public record SnippetSizeBreakdown(@Nullable TokenStatistics statistics, List<Integer> bucketStarts,
                                       List<TopicSizeSeries> series) {
    }

    public record TopicCoverage(String topic, int v2, int v3) {
    }

    public record TopicCoverageSummary(List<TopicCoverage> topics, int maxCount, int totalV2, int totalV3) {
    }

    public record CorpusCoverage(String corpus, int v2, int v3, int shared) {
    }

    /**
     * Size distribution of the AI-generated snippet corpus as a fixed-width histogram stacked by
     * topic: overall statistics, bucket boundaries and one series per top topic (the rest are
     * rolled up into a trailing null-topic series).
     */
    public SnippetSizeBreakdown loadSnippetSizeBreakdown() {
        Map<String, List<Integer>> sizesByTopic = new LinkedHashMap<>();
        List<Integer> allSizes = new ArrayList<>();
        for (Object[] row : vectorStoreRepository.snippetTopicTokenSizes()) {
            String topic = (String) row[0];
            int tokens = ((Number) row[1]).intValue();
            sizesByTopic.computeIfAbsent(topic == null || topic.isBlank() ? null : topic,
                    ignored -> new ArrayList<>()).add(tokens);
            allSizes.add(tokens);
        }
        if (allSizes.isEmpty()) {
            return new SnippetSizeBreakdown(null, List.of(), List.of());
        }

        List<Integer> sorted = allSizes.stream().sorted().toList();
        TokenStatistics statistics = computeStatistics(sorted);
        int min = statistics.min();
        int range = Math.max(1, statistics.max() - min);
        int width = (int) Math.ceil(range / (double) HISTOGRAM_BUCKETS);
        List<Integer> bucketStarts = new ArrayList<>(HISTOGRAM_BUCKETS);
        for (int index = 0; index < HISTOGRAM_BUCKETS; index++) {
            bucketStarts.add(min + index * width);
        }

        // ties on count break by name: SQL row order is unspecified, so without a tie-breaker the
        // top-N split and series order could change between refreshes
        List<Map.Entry<String, List<Integer>>> namedTopics = sizesByTopic.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .sorted(Comparator.comparingInt(
                                (Map.Entry<String, List<Integer>> entry) -> entry.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        List<TopicSizeSeries> series = new ArrayList<>();
        List<Integer> otherSizes = new ArrayList<>(sizesByTopic.getOrDefault(null, List.of()));
        for (int index = 0; index < namedTopics.size(); index++) {
            Map.Entry<String, List<Integer>> entry = namedTopics.get(index);
            if (index < TOP_SIZE_TOPICS) {
                series.add(topicSeries(entry.getKey(), entry.getValue(), min, width));
            } else {
                otherSizes.addAll(entry.getValue());
            }
        }
        if (!otherSizes.isEmpty()) {
            series.add(topicSeries(null, otherSizes, min, width));
        }
        return new SnippetSizeBreakdown(statistics, List.copyOf(bucketStarts), List.copyOf(series));
    }

    private static TokenStatistics computeStatistics(List<Integer> sorted) {
        int count = sorted.size();
        int min = sorted.getFirst();
        int max = sorted.getLast();
        double average = sorted.stream().mapToInt(Integer::intValue).average().orElse(0);
        double median = count % 2 == 1
                ? sorted.get(count / 2)
                : (sorted.get(count / 2 - 1) + sorted.get(count / 2)) / 2.0;
        double standardDeviation = Math.sqrt(sorted.stream()
                .mapToDouble(size -> (size - average) * (size - average))
                .sum() / count);
        return new TokenStatistics(count, min, median, average, max, standardDeviation);
    }

    private static TopicSizeSeries topicSeries(@Nullable String topic, List<Integer> sizes, int min, int width) {
        int[] counts = new int[HISTOGRAM_BUCKETS];
        for (int size : sizes) {
            counts[Math.min(HISTOGRAM_BUCKETS - 1, (size - min) / width)]++;
        }
        double average = sizes.stream().mapToInt(Integer::intValue).average().orElse(0);
        List<Integer> bucketCounts = new ArrayList<>(HISTOGRAM_BUCKETS);
        for (int count : counts) {
            bucketCounts.add(count);
        }
        return new TopicSizeSeries(topic, sizes.size(), average, List.copyOf(bucketCounts));
    }

    /** Coverage of every documentation topic by AI snippets, sorted by total count descending (name breaks ties). */
    public TopicCoverageSummary loadTopicCoverage() {
        Map<String, int[]> countsByTopic = new LinkedHashMap<>();
        for (Object[] row : vectorStoreRepository.countSnippetTopicByVersion()) {
            String topic = (String) row[0];
            if (topic == null || topic.isBlank()) {
                continue;
            }
            String version = (String) row[1];
            int count = ((Number) row[2]).intValue();
            int[] counts = countsByTopic.computeIfAbsent(topic, ignored -> new int[2]);
            // index: 0 = v2 (and unknown), 1 = v3 — mirrors TopicCoverage(topic, v2, v3)
            int index = switch (JmixVersion.fromId(version)) {
                case V2 -> 0;
                case V3 -> 1;
                case null -> 0;
            };
            counts[index] += count;
        }

        List<TopicCoverage> topics = countsByTopic.entrySet().stream()
                .map(entry -> new TopicCoverage(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingInt(VectorStoreAnalyticsService::total).reversed()
                        .thenComparing(TopicCoverage::topic))
                .toList();
        int maxCount = topics.stream()
                .mapToInt(topic -> Math.max(topic.v2(), topic.v3()))
                .max()
                .orElse(1);
        int totalV2 = topics.stream().mapToInt(TopicCoverage::v2).sum();
        int totalV3 = topics.stream().mapToInt(TopicCoverage::v3).sum();
        return new TopicCoverageSummary(topics, maxCount, totalV2, totalV3);
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
            // index: 0 = v2, 1 = v3, 2 = shared/unknown — mirrors CorpusCoverage(corpus, v2, v3, shared)
            int index = switch (JmixVersion.fromId(version)) {
                case V2 -> 0;
                case V3 -> 1;
                case null -> 2;
            };
            counts[index] += count;
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
