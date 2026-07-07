package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.DataManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Aggregates answer-check results for the analytics dashboard: score trend across runs,
 * per-category averages, per-check deltas between two runs, and vector store corpus coverage.
 */
@Component
public class CheckAnalyticsService {

    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DataManager dataManager;
    private final VectorStoreRepository vectorStoreRepository;
    private final double passThreshold;

    public CheckAnalyticsService(DataManager dataManager, VectorStoreRepository vectorStoreRepository,
                                 @org.springframework.beans.factory.annotation.Value("${answer-checks.pass-threshold:0.8}") double passThreshold) {
        this.dataManager = dataManager;
        this.vectorStoreRepository = vectorStoreRepository;
        this.passThreshold = passThreshold;
    }

    public double getPassThreshold() {
        return passThreshold;
    }

    public record RunInfo(String id, String label, double score, double accuracy, int passed, int checkCount) {
    }

    public record CategoryAccuracy(String category, double accuracy, int passed, int total) {
    }

    public record CategoryScore(String category, double base, double compare) {
    }

    public record CheckDelta(String question, String category, double base, double compare, double delta) {
    }

    public record ComparisonSummary(int total, int improved, int regressed, int unchanged,
                                    Double baseScore, Double candidateScore,
                                    Double baseAccuracy, Double candidateAccuracy) {
    }

    public record CorpusCoverage(String corpus, int v2, int v3) {
    }

    /** A selectable comparison target: one config on one Jmix version, backed by all its runs. */
    public record ConfigOption(String key, String label, String config, String version,
                               int runs, double meanScore, double meanAccuracy) {
    }

    /**
     * Distinct config x version combinations across all finished runs, each aggregating its runs.
     * These are the selectable sides of the comparison, so the user picks a meaningful, noise-averaged
     * config rather than two arbitrary single runs.
     */
    public List<ConfigOption> configOptions() {
        Map<String, List<CheckRun>> groups = groupRunsByConfigVersion();
        List<ConfigOption> options = new ArrayList<>();
        groups.forEach((key, group) -> {
            String[] p = key.split("\\|", 2);
            double meanScore = group.stream().mapToDouble(r -> r.getScore() != null ? r.getScore() : 0.0).average().orElse(0.0);
            double meanAcc = group.stream().mapToDouble(r -> r.getAccuracy() != null ? r.getAccuracy() : 0.0).average().orElse(0.0);
            String label = "%s \u00b7 %s  (%d run%s)".formatted(p[0], p[1], group.size(), group.size() == 1 ? "" : "s");
            options.add(new ConfigOption(key, label, p[0], p[1], group.size(), round(meanScore), round(meanAcc)));
        });
        options.sort(java.util.Comparator.comparing(ConfigOption::config).thenComparing(ConfigOption::version));
        return options;
    }

    private Map<String, List<CheckRun>> groupRunsByConfigVersion() {
        List<CheckRun> runs = dataManager.load(CheckRun.class).query("e.score is not null").list();
        Map<String, List<CheckRun>> groups = new LinkedHashMap<>();
        for (CheckRun run : runs) {
            if (run.getScore() == null) {
                continue;
            }
            String config = configName(run.getConfigLabel(), run.getParameters());
            String version = run.getJmixVersion() != null ? run.getJmixVersion().getId() : "?";
            groups.computeIfAbsent(config + "|" + version, k -> new ArrayList<>()).add(run);
        }
        return groups;
    }

    private Map<String, double[]> aggregateQuestions(@Nullable ConfigOption option, Map<String, String> categoryOut) {
        Map<String, double[]> perQuestion = new LinkedHashMap<>();
        if (option == null) {
            return perQuestion;
        }
        List<CheckRun> runs = groupRunsByConfigVersion().getOrDefault(option.key(), List.of());
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
        agg.forEach((cat, a) -> result.add(new CategoryScore(cat,
                a[2] == 0 ? 0.0 : round(a[0] / a[2]),
                a[2] == 0 ? 0.0 : round(a[1] / a[2]))));
        result.sort(java.util.Comparator.comparing(CategoryScore::category));
        return result;
    }

    public ComparisonSummary summarizeConfigs(@Nullable ConfigOption base, @Nullable ConfigOption candidate,
                                              List<CheckDelta> deltas) {
        int improved = (int) deltas.stream().filter(d -> d.delta() > 0.0001).count();
        int regressed = (int) deltas.stream().filter(d -> d.delta() < -0.0001).count();
        int unchanged = deltas.size() - improved - regressed;
        return new ComparisonSummary(deltas.size(), improved, regressed, unchanged,
                base != null ? base.meanScore() : null, candidate != null ? candidate.meanScore() : null,
                base != null ? base.meanAccuracy() : null, candidate != null ? candidate.meanAccuracy() : null);
    }

    private static double avg(@Nullable double[] sumCount) {
        return sumCount == null || sumCount[1] == 0 ? 0.0 : sumCount[0] / sumCount[1];
    }

    private String configName(@Nullable String label, @Nullable String parameters) {
        if (label != null && !label.isBlank()) {
            String l = label.toUpperCase();
            if (l.startsWith("MAIN")) {
                return "main";
            }
            if (l.startsWith("NEUTRAL")) {
                return "neutral";
            }
            if (l.contains("SNIPPET")) {
                return "strict";
            }
            return shortConfig(label);
        }
        return detectConfig(parameters);
    }

    /**
     * All finished runs ordered oldest-first, labelled with date, detected config and check count.
     */
    public List<RunInfo> loadRuns() {
        List<CheckRun> runs = dataManager.load(CheckRun.class)
                .query("e.score is not null order by e.createdDate")
                .list();
        List<RunInfo> result = new ArrayList<>(runs.size());
        for (CheckRun run : runs) {
            List<Check> checks = loadChecks(run.getId().toString());
            int count = checks.size();
            int passed = (int) checks.stream()
                    .filter(c -> c.getScore() != null && c.getScore() >= passThreshold)
                    .count();
            result.add(new RunInfo(
                    run.getId().toString(),
                    buildLabel(run) + " · " + count,
                    run.getScore() != null ? run.getScore() : 0.0,
                    count == 0 ? 0.0 : (double) passed / count,
                    passed,
                    count));
        }
        return result;
    }

    /**
     * Accuracy (fraction of passed checks) per category for a run, ordered worst-first.
     */
    public List<CategoryAccuracy> categoryAccuracy(@Nullable String runId) {
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (Check check : loadChecks(runId)) {
            String category = check.getCategory() != null ? check.getCategory() : "?";
            int[] pt = agg.computeIfAbsent(category, k -> new int[2]);
            if (check.getScore() != null && check.getScore() >= passThreshold) {
                pt[0]++;
            }
            pt[1]++;
        }
        List<CategoryAccuracy> result = new ArrayList<>();
        agg.forEach((category, pt) ->
                result.add(new CategoryAccuracy(category, pt[1] == 0 ? 0.0 : (double) pt[0] / pt[1], pt[0], pt[1])));
        result.sort((a, b) -> Double.compare(a.accuracy(), b.accuracy()));
        return result;
    }

    private String buildLabel(CheckRun run) {
        String date = run.getCreatedDate() != null ? run.getCreatedDate().format(LABEL_FORMAT) : "?";
        String version = run.getJmixVersion() != null ? run.getJmixVersion().getId() : "?";
        String config = run.getConfigLabel() != null && !run.getConfigLabel().isBlank()
                ? shortConfig(run.getConfigLabel())
                : detectConfig(run.getParameters());
        return date + " " + version + " " + config;
    }

    private static String shortConfig(String label) {
        return label.length() > 30 ? label.substring(0, 30) + "…" : label;
    }

    /**
     * Best-effort human label for the corpus configuration a run used, read from its parameters YAML.
     */
    private String detectConfig(@Nullable String parameters) {
        if (parameters == null) {
            return "base";
        }
        boolean snippets = parameters.contains("vectorType: docs-snippets")
                || parameters.contains("vectorType: uisamples-snippets");
        boolean javaApi = parameters.matches("(?s).*javaapi_retriever:\\s*\\n\\s*enabled: true.*");
        if (snippets) {
            return "snippets";
        }
        return javaApi ? "javaapi" : "base";
    }

    public List<CategoryScore> categoryComparison(@Nullable String baseRunId, @Nullable String compareRunId) {
        Map<String, Double> base = categoryAverages(baseRunId);
        Map<String, Double> compare = categoryAverages(compareRunId);
        TreeSet<String> categories = new TreeSet<>();
        categories.addAll(base.keySet());
        categories.addAll(compare.keySet());
        List<CategoryScore> result = new ArrayList<>();
        for (String category : categories) {
            result.add(new CategoryScore(category,
                    round(base.getOrDefault(category, 0.0)),
                    round(compare.getOrDefault(category, 0.0))));
        }
        return result;
    }

    private Map<String, Double> categoryAverages(@Nullable String runId) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        for (Check check : loadChecks(runId)) {
            String category = check.getCategory() != null ? check.getCategory() : "?";
            double[] agg = sums.computeIfAbsent(category, k -> new double[2]);
            agg[0] += check.getScore() != null ? check.getScore() : 0.0;
            agg[1] += 1;
        }
        Map<String, Double> result = new LinkedHashMap<>();
        sums.forEach((category, agg) -> result.put(category, agg[1] == 0 ? 0.0 : agg[0] / agg[1]));
        return result;
    }

    /**
     * Per-check comparison between two runs, matched by question. Only questions present in both
     * runs are returned, ordered by delta ascending (biggest regressions first).
     */
    public List<CheckDelta> compareChecks(@Nullable String baseRunId, @Nullable String compareRunId) {
        Map<String, Check> base = byQuestion(baseRunId);
        Map<String, Check> compare = byQuestion(compareRunId);
        List<CheckDelta> result = new ArrayList<>();
        for (Map.Entry<String, Check> entry : compare.entrySet()) {
            Check baseCheck = base.get(entry.getKey());
            if (baseCheck == null) {
                continue;
            }
            double b = score(baseCheck);
            double c = score(entry.getValue());
            result.add(new CheckDelta(entry.getKey(), entry.getValue().getCategory(),
                    round(b), round(c), round(c - b)));
        }
        result.sort((a, b) -> Double.compare(a.delta(), b.delta()));
        return result;
    }

    public List<CorpusCoverage> corpusCoverage() {
        Map<String, int[]> byType = new LinkedHashMap<>();
        for (Object[] row : vectorStoreRepository.countByTypeAndVersion()) {
            String type = (String) row[0];
            String version = (String) row[1];
            int count = (int) row[2];
            if (type == null) {
                continue;
            }
            int[] vv = byType.computeIfAbsent(type, k -> new int[2]);
            if ("v3".equalsIgnoreCase(version)) {
                vv[1] += count;
            } else {
                vv[0] += count;
            }
        }
        List<CorpusCoverage> result = new ArrayList<>();
        byType.forEach((type, vv) -> result.add(new CorpusCoverage(type, vv[0], vv[1])));
        return result;
    }

    /**
     * Summary of a baseline vs candidate comparison: how many checks improved/regressed/unchanged
     * and the score/accuracy of each run.
     */
    public ComparisonSummary summarize(@Nullable String baseId, @Nullable String candidateId,
                                       List<CheckDelta> deltas) {
        int improved = 0;
        int regressed = 0;
        int unchanged = 0;
        for (CheckDelta d : deltas) {
            if (d.delta() > 0.0001) {
                improved++;
            } else if (d.delta() < -0.0001) {
                regressed++;
            } else {
                unchanged++;
            }
        }
        RunInfo base = runById(baseId);
        RunInfo cand = runById(candidateId);
        return new ComparisonSummary(deltas.size(), improved, regressed, unchanged,
                base != null ? base.score() : null, cand != null ? cand.score() : null,
                base != null ? base.accuracy() : null, cand != null ? cand.accuracy() : null);
    }

    @Nullable
    public RunInfo runById(@Nullable String id) {
        if (id == null) {
            return null;
        }
        return loadRuns().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    private Map<String, Check> byQuestion(@Nullable String runId) {
        Map<String, Check> result = new LinkedHashMap<>();
        for (Check check : loadChecks(runId)) {
            if (check.getQuestion() != null) {
                result.put(check.getQuestion(), check);
            }
        }
        return result;
    }

    private List<Check> loadChecks(@Nullable String runId) {
        if (runId == null) {
            return List.of();
        }
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
