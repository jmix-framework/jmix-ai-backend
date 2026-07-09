package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.core.DataManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates answer-check results: the score/accuracy trend across runs (Check runs screen)
 * and the averaged per-config comparison (Compare runs screen).
 */
@Component
public class CheckAnalyticsService {

    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DataManager dataManager;

    public CheckAnalyticsService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public record RunInfo(String label, double score, double accuracy) {
    }

    public record CategoryScore(String category, double base, double compare) {
    }

    public record CheckDelta(String question, String category, double base, double compare, double delta) {
    }

    public record ComparisonSummary(int improved, int regressed, int unchanged,
                                    Double baseScore, Double candidateScore,
                                    Double baseAccuracy, Double candidateAccuracy) {
    }

    /** A selectable comparison target: one config on one Jmix version, backed by all its runs. */
    public record ConfigOption(String config, String label, double meanScore, double meanAccuracy) {
    }

    /**
     * Distinct config x version pairs across all finished runs, each aggregating its runs, so the
     * user compares a meaningful noise-averaged config rather than two arbitrary single runs.
     */
    public List<ConfigOption> configOptions() {
        List<ConfigOption> options = new ArrayList<>();
        groupRunsByConfig().forEach((key, group) -> {
            double meanScore = group.stream().mapToDouble(r -> r.getScore() != null ? r.getScore() : 0.0).average().orElse(0.0);
            double meanAcc = group.stream().mapToDouble(r -> r.getAccuracy() != null ? r.getAccuracy() : 0.0).average().orElse(0.0);
            String[] p = key.split("\\|\\|", 2);
            String label = "[%s] %s  (%d run%s)".formatted(p[1], shortConfig(p[0]), group.size(), group.size() == 1 ? "" : "s");
            options.add(new ConfigOption(key, label, round(meanScore), round(meanAcc)));
        });
        options.sort(java.util.Comparator.comparing(ConfigOption::config));
        return options;
    }

    private Map<String, List<CheckRun>> groupRunsByConfig() {
        List<CheckRun> runs = dataManager.load(CheckRun.class).query("e.score is not null").list();
        Map<String, List<CheckRun>> groups = new LinkedHashMap<>();
        for (CheckRun run : runs) {
            if (run.getScore() == null) {
                continue;
            }
            String key = configKey(run.getConfigLabel()) + "||" + versionId(run);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(run);
        }
        return groups;
    }

    private static String versionId(CheckRun run) {
        return run.getJmixVersion() != null ? run.getJmixVersion().getId() : "?";
    }

    private Map<String, double[]> aggregateQuestions(@Nullable ConfigOption option, Map<String, String> categoryOut) {
        Map<String, double[]> perQuestion = new LinkedHashMap<>();
        if (option == null) {
            return perQuestion;
        }
        List<CheckRun> runs = groupRunsByConfig().getOrDefault(option.config(), List.of());
        for (CheckRun run : runs) {
            for (Check check : loadChecks(run.getId().toString())) {
                String q = check.getQuestion() != null ? check.getQuestion() : "?";
                double[] agg = perQuestion.computeIfAbsent(q, k -> new double[2]);
                agg[0] += score(check);
                agg[1] += 1;
                categoryOut.putIfAbsent(q, check.getCategory() != null ? check.getCategory() : "?");
            }
        }
        return perQuestion;
    }

    /** Per-question averaged deltas between two config options (candidate - baseline). */
    public List<CheckDelta> compareConfigs(@Nullable ConfigOption base, @Nullable ConfigOption candidate) {
        Map<String, String> category = new LinkedHashMap<>();
        Map<String, double[]> b = aggregateQuestions(base, category);
        Map<String, double[]> c = aggregateQuestions(candidate, category);

        java.util.Set<String> questions = new java.util.LinkedHashSet<>();
        questions.addAll(b.keySet());
        questions.addAll(c.keySet());

        List<CheckDelta> deltas = new ArrayList<>();
        for (String q : questions) {
            double bs = avg(b.get(q));
            double cs = avg(c.get(q));
            deltas.add(new CheckDelta(q, category.getOrDefault(q, "?"), round(bs), round(cs), round(cs - bs)));
        }
        deltas.sort(java.util.Comparator.comparingDouble(CheckDelta::delta));
        return deltas;
    }

    public List<CategoryScore> categoryCompareConfigs(List<CheckDelta> deltas) {
        Map<String, double[]> agg = new LinkedHashMap<>();
        for (CheckDelta d : deltas) {
            double[] a = agg.computeIfAbsent(d.category(), k -> new double[3]);
            a[0] += d.base();
            a[1] += d.compare();
            a[2] += 1;
        }
        List<CategoryScore> result = new ArrayList<>();
        agg.forEach((cat, a) -> result.add(new CategoryScore(cat, round(a[0] / a[2]), round(a[1] / a[2]))));
        result.sort(java.util.Comparator.comparing(CategoryScore::category));
        return result;
    }

    public ComparisonSummary summarizeConfigs(@Nullable ConfigOption base, @Nullable ConfigOption candidate,
                                              List<CheckDelta> deltas) {
        int improved = (int) deltas.stream().filter(d -> d.delta() > 0.0001).count();
        int regressed = (int) deltas.stream().filter(d -> d.delta() < -0.0001).count();
        return new ComparisonSummary(improved, regressed, deltas.size() - improved - regressed,
                base != null ? base.meanScore() : null, candidate != null ? candidate.meanScore() : null,
                base != null ? base.meanAccuracy() : null, candidate != null ? candidate.meanAccuracy() : null);
    }

    private static double avg(@Nullable double[] sumCount) {
        return sumCount == null || sumCount[1] == 0 ? 0.0 : sumCount[0] / sumCount[1];
    }

    /** Grouping key for a run's config: the full description (shortened only for display). */
    private static String configKey(@Nullable String label) {
        return label != null && !label.isBlank() ? label : "unlabeled";
    }

    /** All finished runs oldest-first, labelled with date and config, using the stored score/accuracy. */
    public List<RunInfo> loadRuns() {
        return dataManager.load(CheckRun.class)
                .query("e.score is not null order by e.createdDate")
                .list().stream()
                .map(run -> new RunInfo(
                        buildLabel(run),
                        run.getScore() != null ? run.getScore() : 0.0,
                        run.getAccuracy() != null ? run.getAccuracy() : 0.0))
                .toList();
    }

    private String buildLabel(CheckRun run) {
        String date = run.getCreatedDate() != null ? run.getCreatedDate().format(LABEL_FORMAT) : "?";
        return date + " [" + versionId(run) + "] " + shortConfig(configKey(run.getConfigLabel()));
    }

    private static String shortConfig(String label) {
        return label.length() > 30 ? label.substring(0, 30) + "…" : label;
    }

    private List<Check> loadChecks(String runId) {
        return dataManager.load(Check.class)
                .query("e.checkRun.id = :runId")
                .parameter("runId", java.util.UUID.fromString(runId))
                .list();
    }

    private static double score(Check check) {
        return check.getScore() != null ? check.getScore() : 0.0;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
