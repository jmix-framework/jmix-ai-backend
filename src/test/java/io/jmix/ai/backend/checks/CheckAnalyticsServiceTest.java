package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckAnalyticsServiceTest {

    @Test
    void groupIdentityUsesFullParametersSnapshotInsteadOfDisplayLabel() {
        CheckRun first = checkRun("Same label", "description: Same label\nmodel:\n  name: first");
        CheckRun second = checkRun("Same label", "description: Same label\nmodel:\n  name: second");

        assertThat(CheckAnalyticsService.groupKey(first))
                .isNotEqualTo(CheckAnalyticsService.groupKey(second));
    }

    @Test
    void historicalRunUsesDescriptionFromParametersAsDisplayLabel() {
        CheckRun run = checkRun(null, "description: Historical config\nmodel:\n  name: gpt-5");

        assertThat(CheckAnalyticsService.displayConfigLabel(run)).isEqualTo("Historical config");
    }

    @Test
    void groupIdentityIncludesEvaluatorConfigAndSeparatesLegacyRuns() {
        CheckRun legacy = checkRun("Config", "parameters");
        CheckRun first = checkRun("Config", "parameters");
        first.setEvaluatorConfig("semantic-v3|model=first|temperature=0.0");
        CheckRun second = checkRun("Config", "parameters");
        second.setEvaluatorConfig("semantic-v3|model=second|temperature=0.0");

        assertThat(CheckAnalyticsService.groupKey(first))
                .isNotEqualTo(CheckAnalyticsService.groupKey(second))
                .isNotEqualTo(CheckAnalyticsService.groupKey(legacy));
        assertThat(CheckAnalyticsService.configFingerprint(first))
                .isNotEqualTo(CheckAnalyticsService.configFingerprint(second))
                .isNotEqualTo(CheckAnalyticsService.configFingerprint(legacy));
    }

    @Test
    void configFingerprintIsStableAndIncludesFullConfigAndCohort() {
        String parameters = "description: Same config\nmodel:\n  name: first";
        CheckRun first = checkRun("Same config", parameters);
        first.setDefinitionFingerprint("definitions-v1-aaaaaaaaaaaa");
        CheckRun same = checkRun("Same config", parameters);
        same.setDefinitionFingerprint("definitions-v1-aaaaaaaaaaaa");
        CheckRun changedConfig = checkRun(
                "Same config", "description: Same config\nmodel:\n  name: second");
        changedConfig.setDefinitionFingerprint("definitions-v1-aaaaaaaaaaaa");
        CheckRun changedCohort = checkRun("Same config", parameters);
        changedCohort.setDefinitionFingerprint("definitions-v1-bbbbbbbbbbbb");

        assertThat(CheckAnalyticsService.configFingerprint(first))
                .isEqualTo(CheckAnalyticsService.configFingerprint(same))
                .isNotEqualTo(CheckAnalyticsService.configFingerprint(changedConfig))
                .isNotEqualTo(CheckAnalyticsService.configFingerprint(changedCohort));
    }

    @Test
    void compareQuestionsUsesOnlyIntersectionAndKeepsLatestAnswers() {
        Map<CheckAnalyticsService.QuestionKey, CheckAnalyticsService.QuestionAggregate> baseline =
                new LinkedHashMap<>();
        baseline.put(questionKey("common", "reference"),
                aggregate(1.0, 2, "data", "reference", "latest baseline"));
        baseline.put(questionKey("baseline only", "reference"),
                aggregate(1.0, 1, "data", "reference", "baseline"));

        Map<CheckAnalyticsService.QuestionKey, CheckAnalyticsService.QuestionAggregate> candidate =
                new LinkedHashMap<>();
        candidate.put(questionKey("common", "reference"),
                aggregate(1.8, 2, "data", "reference", "latest candidate"));
        candidate.put(questionKey("candidate only", "reference"),
                aggregate(0.7, 1, "data", "reference", "candidate"));

        CheckAnalyticsService.ComparisonResult result =
                CheckAnalyticsService.compareQuestions(baseline, candidate);

        assertThat(result.baselineOnly()).isEqualTo(1);
        assertThat(result.candidateOnly()).isEqualTo(1);
        assertThat(result.deltas()).singleElement().satisfies(delta -> {
            assertThat(delta.question()).isEqualTo("common");
            assertThat(delta.base()).isEqualTo(0.5);
            assertThat(delta.compare()).isEqualTo(0.9);
            assertThat(delta.delta()).isEqualTo(0.4);
            assertThat(delta.referenceAnswer()).isEqualTo("reference");
            assertThat(delta.baselineActualAnswer()).isEqualTo("latest baseline");
            assertThat(delta.candidateActualAnswer()).isEqualTo("latest candidate");
            assertThat(delta.baseAccuracy()).isNull();
            assertThat(delta.candidateAccuracy()).isNull();
        });
    }

    @Test
    void compareQuestionsKeepsAccuracyForTheCommonQuestionSet() {
        Map<CheckAnalyticsService.QuestionKey, CheckAnalyticsService.QuestionAggregate> baseline = Map.of(
                questionKey("common", "reference"),
                aggregate(1.6, 2, 1, 2, "data", "reference", "baseline"));
        Map<CheckAnalyticsService.QuestionKey, CheckAnalyticsService.QuestionAggregate> candidate = Map.of(
                questionKey("common", "reference"),
                aggregate(1.8, 2, 2, 2, "data", "reference", "candidate"));

        CheckAnalyticsService.ComparisonResult result =
                CheckAnalyticsService.compareQuestions(baseline, candidate);

        assertThat(result.deltas()).singleElement().satisfies(delta -> {
            assertThat(delta.baseAccuracy()).isEqualTo(0.5);
            assertThat(delta.candidateAccuracy()).isEqualTo(1.0);
        });
    }

    @Test
    void sameQuestionWithDifferentReferenceAnswersIsNotComparable() {
        Map<CheckAnalyticsService.QuestionKey, CheckAnalyticsService.QuestionAggregate> baseline = Map.of(
                questionKey("same question", "first reference"),
                aggregate(1.0, 1, "data", "first reference", "baseline"));
        Map<CheckAnalyticsService.QuestionKey, CheckAnalyticsService.QuestionAggregate> candidate = Map.of(
                questionKey("same question", "second reference"),
                aggregate(1.0, 1, "data", "second reference", "candidate"));

        CheckAnalyticsService.ComparisonResult result =
                CheckAnalyticsService.compareQuestions(baseline, candidate);

        assertThat(result.deltas()).isEmpty();
        assertThat(result.baselineOnly()).isEqualTo(1);
        assertThat(result.candidateOnly()).isEqualTo(1);
    }

    @Test
    void summaryUsesOnlyMetricsFromCommonQuestions() {
        CheckAnalyticsService service = new CheckAnalyticsService(null);
        CheckAnalyticsService.ComparisonResult comparison = new CheckAnalyticsService.ComparisonResult(
                java.util.List.of(
                        delta(-0.2, 1.0, 0.0),
                        delta(0.0, 0.5, 1.0),
                        delta(0.3, 0.0, 1.0)),
                2,
                3);

        CheckAnalyticsService.ComparisonSummary summary =
                service.summarizeConfigs(comparison);

        assertThat(summary.common()).isEqualTo(3);
        assertThat(summary.improved()).isEqualTo(1);
        assertThat(summary.regressed()).isEqualTo(1);
        assertThat(summary.unchanged()).isEqualTo(1);
        assertThat(summary.baselineOnly()).isEqualTo(2);
        assertThat(summary.candidateOnly()).isEqualTo(3);
        assertThat(summary.baseScore()).isEqualTo(0.5);
        assertThat(summary.candidateScore()).isEqualTo(0.533);
        assertThat(summary.baseAccuracy()).isEqualTo(0.5);
        assertThat(summary.candidateAccuracy()).isEqualTo(0.667);
    }

    @Test
    void summaryHasNoMetricsWithoutCommonQuestions() {
        CheckAnalyticsService.ComparisonSummary summary = new CheckAnalyticsService(null)
                .summarizeConfigs(new CheckAnalyticsService.ComparisonResult(List.of(), 1, 1));

        assertThat(summary.baseScore()).isNull();
        assertThat(summary.candidateScore()).isNull();
        assertThat(summary.baseAccuracy()).isNull();
        assertThat(summary.candidateAccuracy()).isNull();
    }

    @Test
    void configurationsFromDifferentEvaluationCohortsAreFlagged() {
        CheckAnalyticsService.ConfigOption baseline = new CheckAnalyticsService.ConfigOption(
                "base", "Base", "v2", "first-cohort", "defs-1", "semantic-v3", "0.8", "base123", 1);
        CheckAnalyticsService.ConfigOption candidate = new CheckAnalyticsService.ConfigOption(
                "candidate", "Candidate", "v2", "second-cohort", "defs-2", "semantic-v3", "0.8", "candidate123", 1);
        CheckAnalyticsService.ConfigOption sameCohort = new CheckAnalyticsService.ConfigOption(
                "other", "Other", "v2", "first-cohort", "defs-1", "semantic-v3", "0.8", "other123", 1);

        assertThat(CheckAnalyticsService.canCompare(baseline, candidate)).isFalse();
        assertThat(CheckAnalyticsService.canCompare(baseline, sameCohort)).isTrue();
        assertThat(CheckAnalyticsService.canCompare(baseline, null)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewSeparatesRunsThatDifferOnlyByEvaluationCohort() {
        CheckRun runA = checkRun("Config", "same-params");
        runA.setScore(0.8);
        runA.setEvaluatorConfig("evaluator-A");
        CheckRun runB = checkRun("Config", "same-params");
        runB.setScore(0.6);
        runB.setEvaluatorConfig("evaluator-B");

        DataManager dataManager = mock(DataManager.class);
        FluentLoader<CheckRun> runLoader = mock(FluentLoader.class);
        FluentLoader.ByQuery<CheckRun> runQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(CheckRun.class)).thenReturn(runLoader);
        when(runLoader.query("e.score is not null order by e.createdDate, e.id")).thenReturn(runQuery);
        when(runQuery.list()).thenReturn(List.of(runA, runB));

        CheckAnalyticsService.Overview overview = new CheckAnalyticsService(dataManager).loadOverview();

        // same Jmix version and parameters, but different evaluator => two cohorts => two trend lines
        assertThat(overview.trends()).hasSize(2);
        assertThat(overview.configCount()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparisonAccuracyUsesTheStoredPassThreshold() {
        UUID runId = UUID.randomUUID();
        CheckRun run = checkRun("Config", "parameters");
        run.setId(runId);
        run.setScore(0.7);
        run.setAccuracy(1.0);
        run.setEvaluatorConfig("semantic-v3|model=judge|temperature=0.0");
        run.setPassThreshold(0.8);
        Check check = new Check();
        check.setQuestion("common");
        check.setReferenceAnswer("reference");
        check.setScore(0.7);

        DataManager dataManager = mock(DataManager.class);
        FluentLoader<CheckRun> runLoader = mock(FluentLoader.class);
        FluentLoader.ByQuery<CheckRun> runQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(CheckRun.class)).thenReturn(runLoader);
        when(runLoader.query("e.score is not null order by e.createdDate, e.id")).thenReturn(runQuery);
        when(runQuery.list()).thenReturn(List.of(run));
        FluentLoader<Check> checkLoader = mock(FluentLoader.class);
        FluentLoader.ByQuery<Check> checkQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(Check.class)).thenReturn(checkLoader);
        when(checkLoader.query("e.checkRun.id in :runIds order by e.checkRun.createdDate, e.checkRun.id, e.id"))
                .thenReturn(checkQuery);
        when(checkQuery.parameter("runIds", List.of(runId))).thenReturn(checkQuery);
        when(checkQuery.list()).thenReturn(List.of(check));

        CheckAnalyticsService service = new CheckAnalyticsService(dataManager);
        CheckAnalyticsService.ConfigOption option = new CheckAnalyticsService.ConfigOption(
                CheckAnalyticsService.groupKey(run), "Config", "v2",
                CheckAnalyticsService.comparisonCohortKey(run), "defs", "evaluator", "0.8",
                CheckAnalyticsService.configFingerprint(run), 1);

        CheckAnalyticsService.ComparisonSummary summary =
                service.summarizeConfigs(service.compareConfigs(option, option));

        assertThat(summary.baseScore()).isEqualTo(0.7);
        assertThat(summary.candidateScore()).isEqualTo(0.7);
        assertThat(summary.baseAccuracy()).isEqualTo(0.0);
        assertThat(summary.candidateAccuracy()).isEqualTo(0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsChecksForConfigurationRunsWithOneOrderedQuery() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        CheckRun first = checkRun("Config", "parameters");
        first.setId(firstId);
        first.setScore(1.0);
        CheckRun second = checkRun("Config", "parameters");
        second.setId(secondId);
        second.setScore(1.0);
        Check check = new Check();

        DataManager dataManager = mock(DataManager.class);
        FluentLoader<CheckRun> runLoader = mock(FluentLoader.class);
        FluentLoader.ByQuery<CheckRun> runQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(CheckRun.class)).thenReturn(runLoader);
        when(runLoader.query("e.score is not null order by e.createdDate, e.id")).thenReturn(runQuery);
        when(runQuery.list()).thenReturn(List.of(first, second));
        FluentLoader<Check> checkLoader = mock(FluentLoader.class);
        FluentLoader.ByQuery<Check> checkQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(Check.class)).thenReturn(checkLoader);
        when(checkLoader.query("e.checkRun.id in :runIds order by e.checkRun.createdDate, e.checkRun.id, e.id"))
                .thenReturn(checkQuery);
        when(checkQuery.parameter("runIds", List.of(firstId, secondId))).thenReturn(checkQuery);
        when(checkQuery.list()).thenReturn(List.of(check));

        CheckAnalyticsService service = new CheckAnalyticsService(dataManager);
        CheckAnalyticsService.ConfigOption option = new CheckAnalyticsService.ConfigOption(
                CheckAnalyticsService.groupKey(first), "Config", "v2",
                CheckAnalyticsService.comparisonCohortKey(first), "defs", "evaluator", "",
                CheckAnalyticsService.configFingerprint(first), 2);
        CheckAnalyticsService.ComparisonResult result = service.compareConfigs(option, null);

        assertThat(result.baselineOnly()).isEqualTo(1);
        verify(dataManager).load(Check.class);
        verify(checkQuery).parameter("runIds", List.of(firstId, secondId));
        verify(checkQuery).list();
    }

    private static CheckAnalyticsService.QuestionAggregate aggregate(
            double sum, int count, String category, String reference, String actual) {
        return aggregate(sum, count, 0, 0, category, reference, actual);
    }

    private static CheckAnalyticsService.QuestionAggregate aggregate(
            double sum, int count, int passed, int accuracyCount,
            String category, String reference, String actual) {
        return new CheckAnalyticsService.QuestionAggregate(
                sum, count, passed, accuracyCount, category, reference, actual);
    }

    private static CheckAnalyticsService.QuestionKey questionKey(String question, String referenceAnswer) {
        return new CheckAnalyticsService.QuestionKey(question, referenceAnswer);
    }

    private static CheckAnalyticsService.CheckDelta delta(double value) {
        return delta(value, null, null);
    }

    private static CheckAnalyticsService.CheckDelta delta(
            double value, Double baseAccuracy, Double candidateAccuracy) {
        return new CheckAnalyticsService.CheckDelta(
                "question " + value, "category", 0.5, 0.5 + value, value,
                "reference", "baseline", "candidate", baseAccuracy, candidateAccuracy);
    }

    private static CheckRun checkRun(String label, String parameters) {
        CheckRun run = new CheckRun();
        run.setConfigLabel(label);
        run.setParameters(parameters);
        run.setJmixVersion(JmixVersion.V2);
        return run;
    }


}
