package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class CheckRunComparisonService {

    private final DataManager dataManager;

    public CheckRunComparisonService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public ComparisonReport compare(UUID baselineRunId, UUID candidateRunId) {
        List<Check> baselineChecks = loadChecks(baselineRunId);
        List<Check> candidateChecks = loadChecks(candidateRunId);
        return compare(baselineChecks, candidateChecks);
    }

    ComparisonReport compare(List<Check> baselineChecks, List<Check> candidateChecks) {
        Map<String, Check> baselineByKey = baselineChecks.stream()
                .collect(Collectors.toMap(this::buildKey, check -> check, (left, right) -> left, LinkedHashMap::new));
        Map<String, Check> candidateByKey = candidateChecks.stream()
                .collect(Collectors.toMap(this::buildKey, check -> check, (left, right) -> left, LinkedHashMap::new));

        List<ComparisonRow> rows = new ArrayList<>();
        for (String key : unionKeys(baselineByKey, candidateByKey)) {
            Check baseline = baselineByKey.get(key);
            Check candidate = candidateByKey.get(key);
            rows.add(toRow(baseline, candidate));
        }

        rows.sort(Comparator
                .comparing((ComparisonRow row) -> row.delta() == null ? Double.POSITIVE_INFINITY : row.delta())
                .thenComparing(ComparisonRow::question, String.CASE_INSENSITIVE_ORDER));

        return new ComparisonReport(
                buildSummary(rows),
                buildCategorySummaries(rows),
                rows
        );
    }

    private List<Check> loadChecks(UUID runId) {
        return dataManager.load(Check.class)
                .query("select e from Check_ e where e.checkRun.id = :runId order by e.category, e.question")
                .parameter("runId", runId)
                .list();
    }

    private String buildKey(Check check) {
        return (check.getCategory() == null ? "" : check.getCategory()) + "::" +
                (check.getQuestion() == null ? "" : check.getQuestion());
    }

    private List<String> unionKeys(Map<String, Check> baselineByKey, Map<String, Check> candidateByKey) {
        Map<String, Boolean> keys = new LinkedHashMap<>();
        baselineByKey.keySet().forEach(key -> keys.put(key, Boolean.TRUE));
        candidateByKey.keySet().forEach(key -> keys.put(key, Boolean.TRUE));
        return List.copyOf(keys.keySet());
    }

    private ComparisonRow toRow(Check baseline, Check candidate) {
        String category = firstNonBlank(baseline != null ? baseline.getCategory() : null, candidate != null ? candidate.getCategory() : null);
        String question = firstNonBlank(baseline != null ? baseline.getQuestion() : null, candidate != null ? candidate.getQuestion() : null);
        Double baselineScore = baseline != null ? baseline.getScore() : null;
        Double candidateScore = candidate != null ? candidate.getScore() : null;
        Double delta = baselineScore != null && candidateScore != null ? candidateScore - baselineScore : null;

        return new ComparisonRow(
                question,
                category,
                baselineScore,
                candidateScore,
                delta,
                baseline != null ? baseline.getId() : null,
                candidate != null ? candidate.getId() : null,
                baseline != null ? baseline.getActualAnswer() : null,
                candidate != null ? candidate.getActualAnswer() : null,
                baseline != null ? baseline.getLog() : null,
                candidate != null ? candidate.getLog() : null
        );
    }

    private ComparisonSummary buildSummary(List<ComparisonRow> rows) {
        int improved = 0;
        int regressed = 0;
        int unchanged = 0;

        double baselineTotal = 0;
        int baselineCount = 0;
        double candidateTotal = 0;
        int candidateCount = 0;

        for (ComparisonRow row : rows) {
            if (row.baselineScore() != null) {
                baselineTotal += row.baselineScore();
                baselineCount++;
            }
            if (row.candidateScore() != null) {
                candidateTotal += row.candidateScore();
                candidateCount++;
            }
            if (row.delta() == null) {
                continue;
            }
            if (row.delta() > 0.0001d) {
                improved++;
            } else if (row.delta() < -0.0001d) {
                regressed++;
            } else {
                unchanged++;
            }
        }

        Double baselineAverage = baselineCount > 0 ? baselineTotal / baselineCount : null;
        Double candidateAverage = candidateCount > 0 ? candidateTotal / candidateCount : null;
        Double delta = baselineAverage != null && candidateAverage != null ? candidateAverage - baselineAverage : null;

        return new ComparisonSummary(rows.size(), improved, regressed, unchanged, baselineAverage, candidateAverage, delta);
    }

    private List<CategorySummary> buildCategorySummaries(List<ComparisonRow> rows) {
        Map<String, List<ComparisonRow>> rowsByCategory = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> firstNonBlank(row.category(), "uncategorized"),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<CategorySummary> result = new ArrayList<>();
        for (Map.Entry<String, List<ComparisonRow>> entry : rowsByCategory.entrySet()) {
            String category = entry.getKey();
            List<ComparisonRow> categoryRows = entry.getValue();
            result.add(new CategorySummary(
                    category,
                    averageScore(categoryRows, true),
                    averageScore(categoryRows, false),
                    categoryRows.size()
            ));
        }

        result.sort(Comparator.comparing(CategorySummary::category, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private Double averageScore(List<ComparisonRow> rows, boolean baseline) {
        double total = 0;
        int count = 0;
        for (ComparisonRow row : rows) {
            Double score = baseline ? row.baselineScore() : row.candidateScore();
            if (score != null) {
                total += score;
                count++;
            }
        }
        return count > 0 ? total / count : null;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : Objects.requireNonNullElse(second, "");
    }

    public record ComparisonReport(
            ComparisonSummary summary,
            List<CategorySummary> categorySummaries,
            List<ComparisonRow> rows
    ) {
    }

    public record ComparisonSummary(
            int totalQuestions,
            int improvedCount,
            int regressedCount,
            int unchangedCount,
            Double baselineAverage,
            Double candidateAverage,
            Double averageDelta
    ) {
    }

    public record CategorySummary(
            String category,
            Double baselineAverage,
            Double candidateAverage,
            int questionCount
    ) {
        public Double delta() {
            return baselineAverage != null && candidateAverage != null ? candidateAverage - baselineAverage : null;
        }
    }

    public record ComparisonRow(
            String question,
            String category,
            Double baselineScore,
            Double candidateScore,
            Double delta,
            UUID baselineCheckId,
            UUID candidateCheckId,
            String baselineAnswer,
            String candidateAnswer,
            String baselineLog,
            String candidateLog
    ) {
    }
}
