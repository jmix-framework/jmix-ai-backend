package io.jmix.ai.backend.view.checkrun;

import io.jmix.ai.backend.checks.CheckAnalyticsService.ConfigTrend;
import io.jmix.ai.backend.checks.CheckAnalyticsService.RunPoint;
import io.jmix.ai.backend.view.checkrun.CheckRunAnalyticsView.ChartModel;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRunAnalyticsViewTest {

    @Test
    void keepsTwoRunsOfOneConfigWithinTheSameMinute() {
        ChartModel model = CheckRunAnalyticsView.buildModel(List.of(trend(
                point(at(2026, 7, 21, 14, 30, 5)),
                point(at(2026, 7, 21, 14, 30, 45)))));

        assertThat(model.categories()).hasSize(2);
        assertThat(model.pointsByTrend().get(0)).hasSize(2);
    }

    @Test
    void keepsRunsThatShareMonthDayTimeAcrossYears() {
        ChartModel model = CheckRunAnalyticsView.buildModel(List.of(trend(
                point(at(2025, 7, 21, 14, 30, 5)),
                point(at(2026, 7, 21, 14, 30, 5)))));

        assertThat(model.categories()).hasSize(2);
        assertThat(model.pointsByTrend().get(0)).hasSize(2);
    }

    @Test
    void keepsMultipleUndatedRunsApart() {
        ChartModel model = CheckRunAnalyticsView.buildModel(List.of(trend(
                point(null),
                point(null))));

        assertThat(model.categories()).hasSize(2);
        assertThat(model.pointsByTrend().get(0)).hasSize(2);
    }

    @Test
    void alignsRunsOfDifferentConfigsAtTheSameInstantOnOneCategory() {
        OffsetDateTime instant = at(2026, 7, 21, 14, 30, 5);
        ChartModel model = CheckRunAnalyticsView.buildModel(List.of(
                trend(point(instant)),
                trend(point(instant))));

        assertThat(model.categories()).hasSize(1);
        assertThat(model.pointsByTrend()).hasSize(2);
        assertThat(model.pointsByTrend().get(0)).hasSize(1);
        assertThat(model.pointsByTrend().get(1)).hasSize(1);
    }

    private static OffsetDateTime at(int year, int month, int day, int hour, int minute, int second) {
        return OffsetDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC);
    }

    private static RunPoint point(OffsetDateTime createdDate) {
        return new RunPoint(createdDate, 0.5, 0.5);
    }

    private static ConfigTrend trend(RunPoint... points) {
        return new ConfigTrend("config", "v2", "fp", List.of(points));
    }
}
