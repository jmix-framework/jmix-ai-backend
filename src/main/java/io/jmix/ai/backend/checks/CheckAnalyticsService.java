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

    /** Fingerprint of runs that predate the fingerprint column. */
    static final String LEGACY_COHORT = "legacy";

    private final DataManager dataManager;

    public CheckAnalyticsService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    /** Score and accuracy of one completed run; a point on the Overview trend charts. */
    public record RunPoint(@Nullable OffsetDateTime createdDate, double score, @Nullable Double accuracy) {
    }

    /** Chronological run metrics of one configuration (one comparison cohort plus parameters). */
    public record ConfigTrend(String label, String version, String fingerprint, List<RunPoint> points) {
    }

    /** Everything the Overview screen shows: one trend per configuration plus headline counters. */
    public record Overview(List<ConfigTrend> trends, int completedRuns, int configCount,
                           @Nullable Double latestScore, @Nullable Double latestAccuracy) {
    }

    /** Average scores of one category in both compared configurations; a bar pair on the category chart. */
    public record CategoryScore(String category, double base, double compare) {
    }

    /**
     * One row of the question-by-question comparison. Scores are averaged over all runs of each
     * configuration, while the actual answers come from the latest run only — so the shown text
     * may not correspond to the averaged score exactly. Accuracies are null when the pass
     * threshold of the runs is unknown.
     */
    public record CheckDelta(String question, String category, double base, double compare, double delta,
                             String referenceAnswer, String baselineActualAnswer,
                             String candidateActualAnswer, @Nullable Double baseAccuracy,
                             @Nullable Double candidateAccuracy) {
    }

    /**
     * Question-by-question comparison. Deltas cover only questions present in both configurations
     * (matched as {@link QuestionKey}), sorted worst regression first. The counters are questions
     * that exist on one side only — they are excluded from every metric.
     */
    public record ComparisonResult(List<CheckDelta> deltas, int baselineOnly, int candidateOnly) {
    }

    /** Headline cards of the comparison; unchanged means the averaged scores differ by no more than 0.0001. */
    public record ComparisonSummary(int common, int improved, int regressed, int unchanged,
                                    int baselineOnly, int candidateOnly,
                                    @Nullable Double baseScore, @Nullable Double candidateScore,
                                    @Nullable Double baseAccuracy, @Nullable Double candidateAccuracy) {
    }

    /**
     * A selectable configuration in the comparison drop-downs: all finished runs sharing one
     * {@link #groupKey(CheckRun)}. The {@code fingerprint} is a short hash of that key, shown in
     * the label to tell equally-described configurations apart; {@code comparisonCohort} drives
     * {@link #canCompare(ConfigOption, ConfigOption)}.
     */
    public record ConfigOption(String key, String description, String version, String comparisonCohort,
                               String definitionFingerprint, String evaluatorConfig, String passThreshold,
                               String fingerprint, int runCount) {
    }

    /**
     * Identity of a question across runs: the text together with the reference answer, so an
     * edited check definition does not match its former self.
     */
    record QuestionKey(String question, String referenceAnswer) {
    }

    /**
     * Running aggregate of one question over all runs of a configuration: score sum and count for
     * the average, passed and accuracyCount for accuracy (counted only when the runs' pass
     * threshold is known), and the latest actual answer for the details panel.
     */
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
            groups.computeIfAbsent(groupKey(run), key -> new ArrayList<>()).add(run);
        }
        List<ConfigTrend> trends = new ArrayList<>(groups.size());
        groups.forEach((key, group) -> {
            CheckRun sample = group.getLast();
            trends.add(new ConfigTrend(
                    displayConfigLabel(sample),
                    versionId(sample),
                    CheckFingerprints.shortHash(key),
                    group.stream()
                            .map(run -> new RunPoint(run.getCreatedDate(), run.getScore(), run.getAccuracy()))
                            .toList()));
        });
        CheckRun latest = runs.isEmpty() ? null : runs.getLast();
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
            CheckRun sample = group.getLast();
            options.add(new ConfigOption(
                    key,
                    displayConfigLabel(sample),
                    versionId(sample),
                    comparisonCohortKey(sample),
                    definitionFingerprint(sample),
                    defaultString(sample.getEvaluatorConfig()),
                    defaultString(passThresholdKey(sample)),
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

    /**
     * Identity of a configuration: the comparison cohort plus the exact parameters. Groups both the
     * comparison drop-down items and the Overview trend series, so the same parameters evaluated
     * against different check suites, evaluators or thresholds form distinct configurations.
     */
    static String groupKey(CheckRun run) {
        String parameters = run.getParameters();
        return comparisonCohortKey(run) + "||"
                + (parameters != null ? "1" + parameters : "0");
    }

    /**
     * Identity of the evaluation conditions: Jmix version, check definitions and evaluator
     * settings. Scores are directly comparable only within one cohort. For runs whose conditions
     * are unknown (legacy fingerprint or missing evaluator config) the parameters are mixed in,
     * so such runs compare without a warning only against runs of the very same configuration.
     */
    static String comparisonCohortKey(CheckRun run) {
        String fingerprint = definitionFingerprint(run);
        String evaluatorConfig = run.getEvaluatorConfig();
        // pass threshold used to live inside evaluatorConfig; it is a first-class field now but
        // still part of the cohort identity (accuracy is not comparable across thresholds)
        String evaluator = nullableKey(evaluatorConfig) + "|" + nullableKey(passThresholdKey(run));
        String key = versionId(run) + "||" + fingerprint + "||" + evaluator;
        if (LEGACY_COHORT.equals(fingerprint) || evaluatorConfig == null) {
            key += "||" + nullableKey(run.getParameters());
        }
        return key;
    }

    @Nullable
    private static String passThresholdKey(CheckRun run) {
        return run.getPassThreshold() != null ? run.getPassThreshold().toString() : null;
    }

    /** Short hash of {@link #groupKey(CheckRun)}, shown in UI labels to disambiguate configurations. */
    static String configFingerprint(CheckRun run) {
        return CheckFingerprints.shortHash(groupKey(run));
    }

    /**
     * The stored fingerprint of the active check definitions; {@link #LEGACY_COHORT} for runs
     * predating the fingerprint column.
     */
    private static String definitionFingerprint(CheckRun run) {
        String fingerprint = run.getDefinitionFingerprint();
        return fingerprint != null && !fingerprint.isBlank() ? fingerprint : LEGACY_COHORT;
    }

    private static String versionId(CheckRun run) {
        return run.getJmixVersion() != null ? run.getJmixVersion().getId() : "";
    }

    private static String nullableKey(@Nullable String value) {
        return value != null ? "1" + value : "0";
    }

    /**
     * Aggregates every check of every run of the option per question; averaging across runs
     * smooths run-to-run LLM noise.
     */
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

    /**
     * The pass threshold the runs were evaluated with — historical runs keep their own threshold,
     * not the current application setting. Null (no accuracy) when no run carries one.
     */
    private static @Nullable Double passThreshold(List<CheckRun> runs) {
        return runs.stream()
                .map(CheckRun::getPassThreshold)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
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
