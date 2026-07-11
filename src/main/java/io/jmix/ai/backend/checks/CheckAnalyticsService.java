package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.core.DataManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

@Component
public class CheckAnalyticsService {

    static final String LEGACY_COHORT = "legacy";

    private final DataManager dataManager;

    public CheckAnalyticsService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public record RunInfo(@Nullable OffsetDateTime createdDate, String version, String config,
                          double score, @Nullable Double accuracy) {
    }

    public record Overview(List<RunInfo> runs, int completedRuns, int configCount,
                           @Nullable Double latestScore, @Nullable Double latestAccuracy) {
    }

    public record CategoryScore(String category, double base, double compare) {
    }

    public record CheckDelta(String question, String category, double base, double compare, double delta,
                             String referenceAnswer, String baselineActualAnswer,
                             String candidateActualAnswer) {
    }

    public record ComparisonResult(List<CheckDelta> deltas, int baselineOnly, int candidateOnly) {
    }

    public record ComparisonSummary(int common, int improved, int regressed, int unchanged,
                                    int baselineOnly, int candidateOnly,
                                    @Nullable Double baseScore, @Nullable Double candidateScore,
                                    @Nullable Double baseAccuracy, @Nullable Double candidateAccuracy) {
    }

    public record ConfigOption(String key, String description, String version, String fingerprint, int runCount,
                               double meanScore, @Nullable Double meanAccuracy) {
    }

    record QuestionAggregate(double sum, int count, String category, String referenceAnswer,
                             String latestActualAnswer) {

        QuestionAggregate add(Check check) {
            return new QuestionAggregate(
                    sum + score(check),
                    count + 1,
                    defaultString(check.getCategory()),
                    defaultString(check.getReferenceAnswer()),
                    defaultString(check.getActualAnswer()));
        }

        double average() {
            return count == 0 ? 0.0 : sum / count;
        }
    }

    public Overview loadOverview() {
        List<CheckRun> runs = loadFinishedRuns();
        List<RunInfo> runInfos = runs.stream()
                .map(run -> new RunInfo(
                        run.getCreatedDate(),
                        versionId(run),
                        displayConfigLabel(run),
                        run.getScore(),
                        run.getAccuracy()))
                .toList();
        int configCount = (int) runs.stream()
                .map(CheckAnalyticsService::configurationKey)
                .distinct()
                .count();
        CheckRun latest = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        return new Overview(
                runInfos,
                runs.size(),
                configCount,
                latest != null ? latest.getScore() : null,
                latest != null ? latest.getAccuracy() : null);
    }

    public List<ConfigOption> configOptions() {
        List<ConfigOption> options = new ArrayList<>();
        groupRunsByConfig().forEach((key, group) -> {
            CheckRun sample = group.get(group.size() - 1);
            double meanScore = group.stream()
                    .map(CheckRun::getScore)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
            OptionalDouble accuracy = group.stream()
                    .map(CheckRun::getAccuracy)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .average();
            Double meanAccuracy = accuracy.isPresent() ? accuracy.getAsDouble() : null;
            options.add(new ConfigOption(
                    key,
                    displayConfigLabel(sample),
                    versionId(sample),
                    configFingerprint(sample),
                    group.size(),
                    round(meanScore),
                    meanAccuracy != null ? round(meanAccuracy) : null));
        });
        options.sort(Comparator.comparing(ConfigOption::description).thenComparing(ConfigOption::version));
        return options;
    }

    private Map<String, List<CheckRun>> groupRunsByConfig() {
        Map<String, List<CheckRun>> groups = new LinkedHashMap<>();
        for (CheckRun run : loadFinishedRuns()) {
            groups.computeIfAbsent(groupKey(run), key -> new ArrayList<>()).add(run);
        }
        return groups;
    }

    private List<CheckRun> loadFinishedRuns() {
        return dataManager.load(CheckRun.class)
                .query("e.score is not null order by e.createdDate, e.id")
                .list();
    }

    static String groupKey(CheckRun run) {
        String parameters = run.getParameters();
        return versionId(run) + "||" + cohortKey(run) + "||"
                + (parameters != null ? "1" + parameters : "0");
    }

    static String configurationKey(CheckRun run) {
        String parameters = run.getParameters();
        return versionId(run) + "||"
                + (parameters != null ? "1" + parameters : "0");
    }

    static String configFingerprint(CheckRun run) {
        return CheckRunner.shortSha256(groupKey(run));
    }

    private static String cohortKey(CheckRun run) {
        String cohort = CheckRunner.extractCohortKey(run.getConfigLabel());
        return cohort != null ? cohort : LEGACY_COHORT;
    }

    private static String versionId(CheckRun run) {
        return run.getJmixVersion() != null ? run.getJmixVersion().getId() : "";
    }

    private Map<String, QuestionAggregate> aggregateQuestions(
            @Nullable ConfigOption option, Map<String, List<CheckRun>> runsByConfig) {
        Map<String, QuestionAggregate> perQuestion = new LinkedHashMap<>();
        if (option == null) {
            return perQuestion;
        }
        List<CheckRun> runs = runsByConfig.getOrDefault(option.key(), List.of());
        for (Check check : loadChecks(runs)) {
            String question = defaultString(check.getQuestion());
            perQuestion.compute(question, (key, aggregate) ->
                    (aggregate != null ? aggregate : emptyAggregate()).add(check));
        }
        return perQuestion;
    }

    private static QuestionAggregate emptyAggregate() {
        return new QuestionAggregate(0.0, 0, "", "", "");
    }

    public ComparisonResult compareConfigs(@Nullable ConfigOption base, @Nullable ConfigOption candidate) {
        Map<String, List<CheckRun>> runsByConfig = groupRunsByConfig();
        return compareQuestions(
                aggregateQuestions(base, runsByConfig),
                aggregateQuestions(candidate, runsByConfig));
    }

    static ComparisonResult compareQuestions(Map<String, QuestionAggregate> baseline,
                                              Map<String, QuestionAggregate> candidate) {
        Set<String> common = new LinkedHashSet<>(baseline.keySet());
        common.retainAll(candidate.keySet());

        List<CheckDelta> deltas = new ArrayList<>(common.size());
        for (String question : common) {
            QuestionAggregate base = baseline.get(question);
            QuestionAggregate compare = candidate.get(question);
            double baseScore = base.average();
            double candidateScore = compare.average();
            deltas.add(new CheckDelta(
                    question,
                    firstNonBlank(compare.category(), base.category()),
                    round(baseScore),
                    round(candidateScore),
                    round(candidateScore - baseScore),
                    firstNonBlank(compare.referenceAnswer(), base.referenceAnswer()),
                    base.latestActualAnswer(),
                    compare.latestActualAnswer()));
        }
        deltas.sort(Comparator.comparingDouble(CheckDelta::delta));
        return new ComparisonResult(
                List.copyOf(deltas),
                baseline.size() - common.size(),
                candidate.size() - common.size());
    }

    public List<CategoryScore> categoryCompareConfigs(List<CheckDelta> deltas) {
        Map<String, double[]> aggregate = new LinkedHashMap<>();
        for (CheckDelta delta : deltas) {
            double[] values = aggregate.computeIfAbsent(delta.category(), key -> new double[3]);
            values[0] += delta.base();
            values[1] += delta.compare();
            values[2] += 1;
        }
        List<CategoryScore> result = new ArrayList<>();
        aggregate.forEach((category, values) -> result.add(new CategoryScore(
                category,
                round(values[0] / values[2]),
                round(values[1] / values[2]))));
        result.sort(Comparator.comparing(CategoryScore::category));
        return result;
    }

    public ComparisonSummary summarizeConfigs(@Nullable ConfigOption base, @Nullable ConfigOption candidate,
                                              ComparisonResult comparison) {
        List<CheckDelta> deltas = comparison.deltas();
        int improved = (int) deltas.stream().filter(delta -> delta.delta() > 0.0001).count();
        int regressed = (int) deltas.stream().filter(delta -> delta.delta() < -0.0001).count();
        return new ComparisonSummary(
                deltas.size(),
                improved,
                regressed,
                deltas.size() - improved - regressed,
                comparison.baselineOnly(),
                comparison.candidateOnly(),
                base != null ? base.meanScore() : null,
                candidate != null ? candidate.meanScore() : null,
                base != null ? base.meanAccuracy() : null,
                candidate != null ? candidate.meanAccuracy() : null);
    }

    private List<Check> loadChecks(List<CheckRun> runs) {
        List<UUID> runIds = runs.stream()
                .map(CheckRun::getId)
                .toList();
        if (runIds.isEmpty()) {
            return List.of();
        }
        return dataManager.load(Check.class)
                .query("e.checkRun.id in :runIds order by e.checkRun.createdDate, e.checkRun.id, e.id")
                .parameter("runIds", runIds)
                .list();
    }

    private static double score(Check check) {
        return check.getScore() != null ? check.getScore() : 0.0;
    }

    static String displayConfigLabel(CheckRun run) {
        String label = CheckRunner.stripCohortSuffix(run.getConfigLabel());
        if (label == null || label.isBlank()) {
            label = CheckRunner.extractConfigLabel(run.getParameters());
        }
        return configLabel(label);
    }

    private static String configLabel(@Nullable String label) {
        return label != null && !label.isBlank() ? label : "";
    }

    private static String defaultString(@Nullable String value) {
        return value != null ? value : "";
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return !preferred.isBlank() ? preferred : fallback;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
