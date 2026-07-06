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

    public CheckAnalyticsService(DataManager dataManager, VectorStoreRepository vectorStoreRepository) {
        this.dataManager = dataManager;
        this.vectorStoreRepository = vectorStoreRepository;
    }

    public record RunInfo(String id, String label, double score, int checkCount) {
    }

    public record CategoryScore(String category, double base, double compare) {
    }

    public record CheckDelta(String question, String category, double base, double compare, double delta) {
    }

    public record CorpusCoverage(String corpus, int v2, int v3) {
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
            int count = loadChecks(run.getId().toString()).size();
            result.add(new RunInfo(
                    run.getId().toString(),
                    buildLabel(run) + " · " + count,
                    run.getScore() != null ? run.getScore() : 0.0,
                    count));
        }
        return result;
    }

    private String buildLabel(CheckRun run) {
        String date = run.getCreatedDate() != null ? run.getCreatedDate().format(LABEL_FORMAT) : "?";
        return date + " " + detectConfig(run.getParameters());
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
