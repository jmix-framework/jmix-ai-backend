package io.jmix.ai.backend.view.checkanalytics;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckAnalyticsService;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import com.vaadin.flow.component.html.Span;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

@Route(value = "check-analytics", layout = MainView.class)
@ViewController(id = "CheckAnalyticsView")
@ViewDescriptor(path = "check-analytics-view.xml")
public class CheckAnalyticsView extends StandardView {

    private static final int MAX_QUESTION_LABEL = 45;

    @Autowired
    private CheckAnalyticsService analyticsService;

    @ViewComponent
    private Chart trendChart;
    @ViewComponent
    private Chart categoryChart;
    @ViewComponent
    private Chart deltaChart;
    @ViewComponent
    private Chart coverageChart;
    @ViewComponent
    private Chart accuracyChart;
    @ViewComponent
    private Span kpiLabel;
    @ViewComponent
    private JmixComboBox<CheckAnalyticsService.RunInfo> baseRunField;
    @ViewComponent
    private JmixComboBox<CheckAnalyticsService.RunInfo> compareRunField;

    private List<CheckAnalyticsService.RunInfo> runs;

    @Subscribe
    public void onInit(final InitEvent event) {
        runs = analyticsService.loadRuns();

        buildTrend();
        buildCoverage();

        baseRunField.setItems(runs);
        compareRunField.setItems(runs);
        baseRunField.setItemLabelGenerator(CheckAnalyticsService.RunInfo::label);
        compareRunField.setItemLabelGenerator(CheckAnalyticsService.RunInfo::label);

        if (runs.size() >= 2) {
            baseRunField.setValue(runs.get(runs.size() - 2));
            compareRunField.setValue(runs.get(runs.size() - 1));
        } else if (runs.size() == 1) {
            baseRunField.setValue(runs.get(0));
            compareRunField.setValue(runs.get(0));
        }

        baseRunField.addValueChangeListener(e -> refreshComparison());
        compareRunField.addValueChangeListener(e -> refreshComparison());

        refreshComparison();
    }

    private void buildTrend() {
        List<MapDataItem> items = new ArrayList<>();
        for (CheckAnalyticsService.RunInfo run : runs) {
            items.add(new MapDataItem(java.util.Map.of(
                    "label", run.label(), "score", run.score(), "accuracy", run.accuracy())));
        }
        trendChart.setDataSet(new DataSet().withSource(
                new DataSet.Source<MapDataItem>()
                        .withDataProvider(new ListChartItems<>(items))
                        .withCategoryField("label")
                        .withValueFields("score", "accuracy")));
    }

    private void buildCoverage() {
        List<MapDataItem> items = new ArrayList<>();
        for (CheckAnalyticsService.CorpusCoverage c : analyticsService.corpusCoverage()) {
            items.add(new MapDataItem(java.util.Map.of("corpus", c.corpus(), "v2", c.v2(), "v3", c.v3())));
        }
        coverageChart.setDataSet(new DataSet().withSource(
                new DataSet.Source<MapDataItem>()
                        .withDataProvider(new ListChartItems<>(items))
                        .withCategoryField("corpus")
                        .withValueFields("v2", "v3")));
    }

    private void refreshComparison() {
        String baseId = baseRunField.getValue() != null ? baseRunField.getValue().id() : null;
        String compareId = compareRunField.getValue() != null ? compareRunField.getValue().id() : null;

        updateKpi();

        List<MapDataItem> accuracyItems = new ArrayList<>();
        for (CheckAnalyticsService.CategoryAccuracy ca : analyticsService.categoryAccuracy(compareId)) {
            accuracyItems.add(new MapDataItem(java.util.Map.of(
                    "category", ca.category() + " (" + ca.passed() + "/" + ca.total() + ")",
                    "accuracy", ca.accuracy())));
        }
        accuracyChart.setDataSet(new DataSet().withSource(
                new DataSet.Source<MapDataItem>()
                        .withDataProvider(new ListChartItems<>(accuracyItems))
                        .withCategoryField("category")
                        .withValueField("accuracy")));

        List<MapDataItem> categoryItems = new ArrayList<>();
        for (CheckAnalyticsService.CategoryScore cs : analyticsService.categoryComparison(baseId, compareId)) {
            categoryItems.add(new MapDataItem(java.util.Map.of(
                    "category", cs.category(), "base", cs.base(), "compare", cs.compare())));
        }
        categoryChart.setDataSet(new DataSet().withSource(
                new DataSet.Source<MapDataItem>()
                        .withDataProvider(new ListChartItems<>(categoryItems))
                        .withCategoryField("category")
                        .withValueFields("base", "compare")));

        List<MapDataItem> deltaItems = new ArrayList<>();
        for (CheckAnalyticsService.CheckDelta d : analyticsService.compareChecks(baseId, compareId)) {
            double delta = d.delta();
            deltaItems.add(new MapDataItem(java.util.Map.of(
                    "question", shorten(d.question()),
                    "up", delta > 0 ? delta : 0.0,
                    "down", delta < 0 ? delta : 0.0)));
        }
        deltaChart.setDataSet(new DataSet().withSource(
                new DataSet.Source<MapDataItem>()
                        .withDataProvider(new ListChartItems<>(deltaItems))
                        .withCategoryField("question")
                        .withValueFields("up", "down")));
    }

    private void updateKpi() {
        CheckAnalyticsService.RunInfo run = compareRunField.getValue();
        if (run == null) {
            kpiLabel.setText("");
            return;
        }
        int threshold = (int) Math.round(analyticsService.getPassThreshold() * 100);
        kpiLabel.setText("Accuracy: %d/%d = %.0f%%  (score ≥ %d%%)   ·   avg score %.3f".formatted(
                run.passed(), run.checkCount(), run.accuracy() * 100, threshold, run.score()));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_QUESTION_LABEL ? oneLine.substring(0, MAX_QUESTION_LABEL) + "…" : oneLine;
    }
}
