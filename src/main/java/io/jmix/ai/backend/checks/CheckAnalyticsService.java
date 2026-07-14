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
import java.util.stream.DoubleStream;

@Component
public class CheckAnalyticsService {

    static final String LEGACY_COHORT = "legacy";

    private final DataManager dataManager;

    public CheckAnalyticsService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public record RunPoint(@Nullable OffsetDateTime createdDate, double score, @Nullable Double accuracy) {
    }

    /** Chronological run metrics of one configuration (same Jmix version and parameters). */
    public record ConfigTrend(String label, String version, String fingerprint, List<RunPoint> points) {
    }

    public record Overview(List<ConfigTrend> trends, int completedRuns, int configCount,
                           @Nullable Double latestScore, @Nullable Double latestAccuracy) {
    }

    public record CategoryScore(String category, double base, double compare) {
    }

    public record CheckDelta(String question, String category, double base, double compare, double delta,
                             String referenceAnswer, String baselineActualAnswer,
                             String candidateActualAnswer, @Nullable Double baseAccuracy,
                             @Nullable Double candidateAccuracy) {
    }

    public record ComparisonResult(List<CheckDelta> deltas, int baselineOnly, int candidateOnly) {
    }

    public record ComparisonSummary(int common, int improved, int regressed, int unchanged,
                                    int baselineOnly, int candidateOnly,
                                    @Nullable Double baseScore, @Nullable Double candidateScore,
                                    @Nullable Double baseAccuracy, @Nullable Double candidateAccuracy) {
    }

    public record ConfigOption(String key, String description, String version, String comparisonCohort,
                               String definitionFingerprint, String evaluatorConfig,
                               String fingerprint, int runCount) {
    }

    record QuestionKey(String question, String referenceAnswer) {
    }

    record QuestionAggregate(double sum, int count, int passed, int accuracyCount,
                             String category, String referenceAnswer, String latestActualAnswer) {

        QuestionAggregate add(Check check, @Nullable Double passThreshold) {
            double checkScore = score(check);
            return new QuestionAggregate(
                    sum + checkScore,
                    count + 1,
                    passed + (passThreshold != null && checkScore >= passThreshold ? 1 : 0),
                    accuracyCount + (passThreshold != null ? 1 : 0),
                    defaultString(check.getCategory()),
                    defaultString(check.getReferenceAnswer()),
                    defaultString(check.getActualAnswer()));
        }

        double average() {
            return count == 0 ? 0.0 : sum / count;
        }

        @Nullable Double accuracy() {
            return accuracyCount == 0 ? null : (double) passed / accuracyCount;
        }
    }

    public Overview loadOverview() {
        List<CheckRun> runs = loadFinishedRuns();
        Map<String, List<CheckRun>> groups = new LinkedHashMap<>();
        for (CheckRun run : runs) {
            groups.computeIfAbsent(configurationKey(run), key -> new ArrayList<>()).add(run);
        }
        List<ConfigTrend> trends = new ArrayList<>(groups.size());
        groups.forEach((key, group) -> {
            CheckRun sample = group.get(group.size() - 1);
            trends.add(new ConfigTrend(
                    displayConfigLabel(sample),
                    versionId(sample),
                    CheckFingerprints.shortHash(key),
                    group.stream()
                            .map(run -> new RunPoint(run.getCreatedDate(), run.getScore(), run.getAccuracy()))
                            .toList()));
        });
        CheckRun latest = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        return new Overview(
                List.copyOf(trends),
                runs.size(),
                trends.size(),
                latest != null ? latest.getScore() : null,
                latest != null ? latest.getAccuracy() : null);
    }

    public List<ConfigOption> configOptions() {
        List<ConfigOption> options = new ArrayList<>();
        groupRunsByConfig().forEach((key, group) -> {
            CheckRun sample = group.get(group.size() - 1);
            options.add(new ConfigOption(
                    key,
                    displayConfigLabel(sample),
                    versionId(sample),
                    comparisonCohortKey(sample),
                    definitionFingerprint(sample),
                    defaultString(sample.getEvaluatorConfig()),
                    configFingerprint(sample),
                    group.size()));
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
        return comparisonCohortKey(run) + "||"
                + (parameters != null ? "1" + parameters : "0");
    }

    static String comparisonCohortKey(CheckRun run) {
        String fingerprint = definitionFingerprint(run);
        String evaluatorConfig = run.getEvaluatorConfig();
        String key = versionId(run) + "||" + fingerprint + "||" + nullableKey(evaluatorConfig);
        if (LEGACY_COHORT.equals(fingerprint) || evaluatorConfig == null) {
            key += "||" + nullableKey(run.getParameters());
        }
        return key;
    }

    static String configurationKey(CheckRun run) {
        String parameters = run.getParameters();
        return versionId(run) + "||"
                + (parameters != null ? "1" + parameters : "0");
    }

    static String configFingerprint(CheckRun run) {
        return CheckFingerprints.shortHash(groupKey(run));
    }

    private static String definitionFingerprint(CheckRun run) {
        String fingerprint = run.getDefinitionFingerprint();
        if (fingerprint != null && !fingerprint.isBlank()) {
            return fingerprint;
        }
        String legacyFingerprint = CheckFingerprints.fromLegacyLabel(run.getConfigLabel());
        return legacyFingerprint != null ? legacyFingerprint : LEGACY_COHORT;
    }

    private static String versionId(CheckRun run) {
        return run.getJmixVersion() != null ? run.getJmixVersion().getId() : "";
    }

    private static String nullableKey(@Nullable String value) {
        return value != null ? "1" + value : "0";
    }

    private Map<QuestionKey, QuestionAggregate> aggregateQuestions(
            @Nullable ConfigOption option, Map<String, List<CheckRun>> runsByConfig) {
        Map<QuestionKey, QuestionAggregate> perQuestion = new LinkedHashMap<>();
        if (option == null) {
            return perQuestion;
        }
        List<CheckRun> runs = runsByConfig.getOrDefault(option.key(), List.of());
        Double passThreshold = passThreshold(runs);
        for (Check check : loadChecks(runs)) {
            QuestionKey key = new QuestionKey(
                    defaultString(check.getQuestion()), defaultString(check.getReferenceAnswer()));
            perQuestion.compute(key, (ignored, aggregate) ->
                    (aggregate != null ? aggregate : emptyAggregate()).add(check, passThreshold));
        }
        return perQuestion;
    }

    private static QuestionAggregate emptyAggregate() {
        return new QuestionAggregate(0.0, 0, 0, 0, "", "", "");
    }

    private static @Nullable Double passThreshold(List<CheckRun> runs) {
        String marker = "|passThreshold=";
        for (CheckRun run : runs) {
            String evaluatorConfig = run.getEvaluatorConfig();
            if (evaluatorConfig == null) {
                continue;
            }
            int start = evaluatorConfig.indexOf(marker);
            if (start < 0) {
                continue;
            }
            start += marker.length();
            int end = evaluatorConfig.indexOf('|', start);
            String value = end >= 0
                    ? evaluatorConfig.substring(start, end)
                    : evaluatorConfig.substring(start);
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public ComparisonResult compareConfigs(@Nullable ConfigOption base, @Nullable ConfigOption candidate) {
        Map<String, List<CheckRun>> runsByConfig = groupRunsByConfig();
        return compareQuestions(
                aggregateQuestions(base, runsByConfig),
                aggregateQuestions(candidate, runsByConfig));
    }

    /**
     * Whether the two configurations were evaluated under the same conditions (Jmix version, check
     * definitions, evaluator). Cross-cohort comparison is allowed but the scores are less reliable.
     */
    public static boolean canCompare(@Nullable ConfigOption base, @Nullable ConfigOption candidate) {
        return base == null || candidate == null
                || Objects.equals(base.comparisonCohort(), candidate.comparisonCohort());
    }

    static ComparisonResult compareQuestions(Map<QuestionKey, QuestionAggregate> baseline,
                                              Map<QuestionKey, QuestionAggregate> candidate) {
        Set<QuestionKey> common = new LinkedHashSet<>(baseline.keySet());
        common.retainAll(candidate.keySet());

        List<CheckDelta> deltas = new ArrayList<>(common.size());
        for (QuestionKey key : common) {
            QuestionAggregate base = baseline.get(key);
            QuestionAggregate compare = candidate.get(key);
            double baseScore = base.average();
            double candidateScore = compare.average();
            deltas.add(new CheckDelta(
                    key.question(),
                    firstNonBlank(compare.category(), base.category()),
                    round(baseScore),
                    round(candidateScore),
                    round(candidateScore - baseScore),
                    firstNonBlank(compare.referenceAnswer(), base.referenceAnswer()),
                    base.latestActualAnswer(),
                    compare.latestActualAnswer(),
                    base.accuracy(),
                    compare.accuracy()));
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

    public ComparisonSummary summarizeConfigs(ComparisonResult comparison) {
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
                averageScore(deltas.stream().mapToDouble(CheckDelta::base)),
                averageScore(deltas.stream().mapToDouble(CheckDelta::compare)),
                averageAccuracy(deltas.stream().map(CheckDelta::baseAccuracy).toList()),
                averageAccuracy(deltas.stream().map(CheckDelta::candidateAccuracy).toList()));
    }

    private static @Nullable Double averageScore(DoubleStream scores) {
        OptionalDouble average = scores.average();
        return average.isPresent() ? round(average.getAsDouble()) : null;
    }

    private static @Nullable Double averageAccuracy(List<Double> accuracies) {
        if (accuracies.isEmpty() || accuracies.contains(null)) {
            return null;
        }
        return round(accuracies.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElseThrow());
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
        return configLabel(CheckConfigLabel.resolve(run.getConfigLabel(), run.getParameters()));
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
