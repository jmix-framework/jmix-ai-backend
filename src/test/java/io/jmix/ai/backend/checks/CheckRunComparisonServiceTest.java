package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class CheckRunComparisonServiceTest {

    private final CheckRunComparisonService comparisonService = new CheckRunComparisonService(null);

    @Test
    void compareBuildsQuestionLevelDeltasAndSummary() {
        Check baselineDocs = check("docs", "Q1", 0.4, "baseline-1");
        Check baselineUi = check("ui", "Q2", 0.8, "baseline-2");
        Check candidateDocs = check("docs", "Q1", 0.9, "candidate-1");
        Check candidateUi = check("ui", "Q2", 0.2, "candidate-2");

        CheckRunComparisonService.ComparisonReport report = comparisonService.compare(
                List.of(baselineDocs, baselineUi),
                List.of(candidateDocs, candidateUi)
        );

        assertThat(report.rows()).hasSize(2);
        assertThat(report.summary().improvedCount()).isEqualTo(1);
        assertThat(report.summary().regressedCount()).isEqualTo(1);
        assertThat(report.summary().unchangedCount()).isEqualTo(0);
        assertThat(report.summary().baselineAverage()).isCloseTo(0.6, offset(0.0001));
        assertThat(report.summary().candidateAverage()).isCloseTo(0.55, offset(0.0001));
    }

    @Test
    void compareKeepsRowsEvenWhenOneRunMissesQuestion() {
        Check baselineDocs = check("docs", "Q1", 0.4, "baseline-1");
        Check candidateUi = check("ui", "Q2", 0.2, "candidate-2");

        CheckRunComparisonService.ComparisonReport report = comparisonService.compare(
                List.of(baselineDocs),
                List.of(candidateUi)
        );

        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows()).anyMatch(row -> row.question().equals("Q1") && row.candidateScore() == null);
        assertThat(report.rows()).anyMatch(row -> row.question().equals("Q2") && row.baselineScore() == null);
    }

    private Check check(String category, String question, double score, String actualAnswer) {
        Check check = new Check();
        check.setCategory(category);
        check.setQuestion(question);
        check.setScore(score);
        check.setActualAnswer(actualAnswer);
        return check;
    }
}
