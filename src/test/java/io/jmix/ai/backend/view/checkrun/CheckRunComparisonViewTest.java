package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.html.Span;
import io.jmix.ai.backend.checks.CheckAnalyticsService.ConfigOption;
import io.jmix.flowui.view.MessageBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckRunComparisonViewTest {

    private final MessageBundle messageBundle = mock(MessageBundle.class);
    private final Span cohortWarning = new Span();
    private final CheckRunComparisonView view = new CheckRunComparisonView();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(view, "messageBundle", messageBundle);
        ReflectionTestUtils.setField(view, "cohortWarning", cohortWarning);
    }

    // version, definitions and evaluator identical; only the pass threshold (and therefore the
    // comparison cohort) differs, so the warning must name the threshold, not "legacy parameters".
    @Test
    void warnsAboutThePassThresholdWhenItIsTheOnlyDifference() {
        when(messageBundle.getMessage("cohortWarning.passThreshold")).thenReturn("different pass threshold");
        ConfigOption base = option("cohort-0.8", "0.8");
        ConfigOption candidate = option("cohort-0.9", "0.9");

        view.updateCohortWarning(base, candidate);

        verify(messageBundle).getMessage("cohortWarning.passThreshold");
        verify(messageBundle, never()).getMessage("cohortWarning.parameters");
        assertThat(cohortWarning.isVisible()).isTrue();
    }

    @Test
    void fallsBackToParametersWhenNoNamedFacetDiffers() {
        when(messageBundle.getMessage("cohortWarning.parameters")).thenReturn("different legacy run parameters");
        // same version/defs/evaluator/threshold, but a different (legacy) comparison cohort
        ConfigOption base = option("legacy-A", "0.8");
        ConfigOption candidate = option("legacy-B", "0.8");

        view.updateCohortWarning(base, candidate);

        verify(messageBundle).getMessage("cohortWarning.parameters");
        verify(messageBundle, never()).getMessage("cohortWarning.passThreshold");
        assertThat(cohortWarning.isVisible()).isTrue();
    }

    @Test
    void hidesTheWarningForComparableConfigurations() {
        ConfigOption base = option("same-cohort", "0.8");
        ConfigOption candidate = option("same-cohort", "0.8");

        view.updateCohortWarning(base, candidate);

        assertThat(cohortWarning.isVisible()).isFalse();
        verify(messageBundle, never()).getMessage(anyString());
    }

    private static ConfigOption option(String comparisonCohort, String passThreshold) {
        return new ConfigOption("key", "Config", "v2", comparisonCohort,
                "defs", "semantic-evaluator-version-2026-07-28", passThreshold, "fp", 1);
    }
}
