package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
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
    void cohortHashIsStableAcrossDefinitionOrderAndChangesWithDefinitionContent() {
        CheckDef first = checkDef(
                "10000000-0000-0000-0000-000000000001", "data", "Question 1", "Answer 1");
        CheckDef second = checkDef(
                "10000000-0000-0000-0000-000000000002", "ui", "Question 2", "Answer 2");

        String forward = CheckRunner.buildCohortKey(List.of(first, second));
        String reversed = CheckRunner.buildCohortKey(List.of(second, first));
        second.setAnswer("Changed answer");
        String changed = CheckRunner.buildCohortKey(List.of(first, second));

        assertThat(forward).startsWith(CheckRunner.EVALUATOR_VERSION + "-").isEqualTo(reversed);
        assertThat(changed).isNotEqualTo(forward);
    }

    @Test
    void cohortSuffixIsIdempotentAndKeepsConfigLabelWithinColumnLimit() {
        String cohort = "semantic-v3-123456789abc";

        String once = CheckRunner.withCohortSuffix("x".repeat(300), cohort);
        String twice = CheckRunner.withCohortSuffix(once, cohort);

        assertThat(once).hasSize(255).isEqualTo(twice);
        assertThat(CheckRunner.extractCohortKey(once)).isEqualTo(cohort);
        assertThat(CheckRunner.stripCohortSuffix(once)).doesNotContain("[cohort:");
    }

    @Test
    void groupIdentitySeparatesLegacyAndNamedCohortsWhileDisplayHidesSuffix() {
        String parameters = "description: Same config\nmodel:\n  name: gpt-5";
        CheckRun legacy = checkRun("Same config", parameters);
        CheckRun firstCohort = checkRun(
                CheckRunner.withCohortSuffix("Same config", "semantic-v3-aaaaaaaaaaaa"), parameters);
        CheckRun secondCohort = checkRun(
                CheckRunner.withCohortSuffix("Same config", "semantic-v3-bbbbbbbbbbbb"), parameters);

        assertThat(CheckAnalyticsService.groupKey(legacy))
                .contains("||" + CheckAnalyticsService.LEGACY_COHORT + "||")
                .isNotEqualTo(CheckAnalyticsService.groupKey(firstCohort));
        assertThat(CheckAnalyticsService.groupKey(firstCohort))
                .isNotEqualTo(CheckAnalyticsService.groupKey(secondCohort));
        assertThat(CheckAnalyticsService.configurationKey(legacy))
                .isEqualTo(CheckAnalyticsService.configurationKey(firstCohort))
                .isEqualTo(CheckAnalyticsService.configurationKey(secondCohort));
        assertThat(CheckAnalyticsService.displayConfigLabel(firstCohort)).isEqualTo("Same config");
    }

    @Test
    void configFingerprintIsStableAndIncludesFullConfigAndCohort() {
        String parameters = "description: Same config\nmodel:\n  name: first";
        CheckRun first = checkRun(
                CheckRunner.withCohortSuffix("Same config", "semantic-v3-aaaaaaaaaaaa"), parameters);
        CheckRun same = checkRun(
                CheckRunner.withCohortSuffix("Same config", "semantic-v3-aaaaaaaaaaaa"), parameters);
        CheckRun changedConfig = checkRun(
                CheckRunner.withCohortSuffix("Same config", "semantic-v3-aaaaaaaaaaaa"),
                "description: Same config\nmodel:\n  name: second");
        CheckRun changedCohort = checkRun(
                CheckRunner.withCohortSuffix("Same config", "semantic-v3-bbbbbbbbbbbb"), parameters);

        assertThat(CheckAnalyticsService.configFingerprint(first))
                .isEqualTo(CheckAnalyticsService.configFingerprint(same))
                .isNotEqualTo(CheckAnalyticsService.configFingerprint(changedConfig))
                .isNotEqualTo(CheckAnalyticsService.configFingerprint(changedCohort));
    }

    @Test
    void compareQuestionsUsesOnlyIntersectionAndKeepsLatestAnswers() {
        Map<String, CheckAnalyticsService.QuestionAggregate> baseline = new LinkedHashMap<>();
        baseline.put("common", aggregate(1.0, 2, "data", "reference", "latest baseline"));
        baseline.put("baseline only", aggregate(1.0, 1, "data", "reference", "baseline"));

        Map<String, CheckAnalyticsService.QuestionAggregate> candidate = new LinkedHashMap<>();
        candidate.put("common", aggregate(1.8, 2, "data", "reference", "latest candidate"));
        candidate.put("candidate only", aggregate(0.7, 1, "data", "reference", "candidate"));

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
        });
    }

    @Test
    void summaryReportsCommonAndMissingQuestionCounts() {
        CheckAnalyticsService service = new CheckAnalyticsService(null);
        CheckAnalyticsService.ConfigOption baseline =
                new CheckAnalyticsService.ConfigOption("base", "Base", "v2", "base123", 2, 0.5, 0.4);
        CheckAnalyticsService.ConfigOption candidate =
                new CheckAnalyticsService.ConfigOption(
                        "candidate", "Candidate", "v2", "candidate123", 2, 0.8, 0.7);
        CheckAnalyticsService.ComparisonResult comparison = new CheckAnalyticsService.ComparisonResult(
                java.util.List.of(
                        delta(-0.2),
                        delta(0.0),
                        delta(0.3)),
                2,
                3);

        CheckAnalyticsService.ComparisonSummary summary =
                service.summarizeConfigs(baseline, candidate, comparison);

        assertThat(summary.common()).isEqualTo(3);
        assertThat(summary.improved()).isEqualTo(1);
        assertThat(summary.regressed()).isEqualTo(1);
        assertThat(summary.unchanged()).isEqualTo(1);
        assertThat(summary.baselineOnly()).isEqualTo(2);
        assertThat(summary.candidateOnly()).isEqualTo(3);
        assertThat(summary.baseScore()).isEqualTo(0.5);
        assertThat(summary.candidateScore()).isEqualTo(0.8);
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
                CheckAnalyticsService.configFingerprint(first), 2, 1.0, 1.0);
        CheckAnalyticsService.ComparisonResult result = service.compareConfigs(option, null);

        assertThat(result.baselineOnly()).isEqualTo(1);
        verify(dataManager).load(Check.class);
        verify(checkQuery).parameter("runIds", List.of(firstId, secondId));
        verify(checkQuery).list();
    }

    private static CheckAnalyticsService.QuestionAggregate aggregate(
            double sum, int count, String category, String reference, String actual) {
        return new CheckAnalyticsService.QuestionAggregate(sum, count, category, reference, actual);
    }

    private static CheckAnalyticsService.CheckDelta delta(double value) {
        return new CheckAnalyticsService.CheckDelta(
                "question " + value, "category", 0.5, 0.5 + value, value,
                "reference", "baseline", "candidate");
    }

    private static CheckRun checkRun(String label, String parameters) {
        CheckRun run = new CheckRun();
        run.setConfigLabel(label);
        run.setParameters(parameters);
        run.setJmixVersion(JmixVersion.V2);
        return run;
    }

    private static CheckDef checkDef(String id, String category, String question, String answer) {
        CheckDef checkDef = new CheckDef();
        checkDef.setId(UUID.fromString(id));
        checkDef.setCategory(category);
        checkDef.setQuestion(question);
        checkDef.setAnswer(answer);
        return checkDef;
    }
}
