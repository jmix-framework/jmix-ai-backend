package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckAnalyticsService;
import io.jmix.ai.backend.checks.CheckAnalyticsService.CheckDelta;
import io.jmix.ai.backend.checks.CheckAnalyticsService.ConfigOption;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Route(value = "check-runs/compare", layout = MainView.class)
@ViewController(id = "CheckRun.compare")
@ViewDescriptor(path = "check-run-comparison-view.xml")
public class CheckRunComparisonView extends StandardView {

    private static final int MAX_QUESTION_LABEL = 45;

    @Autowired
    private CheckAnalyticsService analyticsService;

    @ViewComponent
    private JmixComboBox<ConfigOption> baselineRunField;
    @ViewComponent
    private JmixComboBox<ConfigOption> candidateRunField;
    @ViewComponent
    private JmixCheckbox regressionsOnlyField;
    @ViewComponent
    private HorizontalLayout summaryCards;
    @ViewComponent
    private Chart overviewChart;
    @ViewComponent
    private Chart categoryChart;
    @ViewComponent
    private Chart deltaChart;
    @ViewComponent
    private VerticalLayout resultsBox;

    private final Grid<CheckDelta> diffGrid = new Grid<>(CheckDelta.class, false);

    @Subscribe
    public void onInit(final InitEvent event) {
        List<ConfigOption> options = analyticsService.configOptions();
        baselineRunField.setItems(options);
        candidateRunField.setItems(options);
        baselineRunField.setItemLabelGenerator(ConfigOption::label);
        candidateRunField.setItemLabelGenerator(ConfigOption::label);
        baselineRunField.setHelperText("Averaged over all runs of this config — run-to-run noise cancels out");
        candidateRunField.setHelperText("Compared against the baseline, question by question");

        if (!options.isEmpty()) {
            ConfigOption baseline = pick(options, "main", null)
                    .orElse(options.get(0));
            ConfigOption candidate = pick(options, "neutral", baseline.version())
                    .or(() -> pick(options, "neutral", null))
                    .orElse(options.stream().filter(o -> !o.equals(baseline)).findFirst().orElse(baseline));
            baselineRunField.setValue(baseline);
            candidateRunField.setValue(candidate);
        }

        buildDiffGrid();

        baselineRunField.addValueChangeListener(e -> refresh());
        candidateRunField.addValueChangeListener(e -> refresh());
        regressionsOnlyField.addValueChangeListener(e -> refresh());

        refresh();
    }

    private java.util.Optional<ConfigOption> pick(List<ConfigOption> options, String config, String version) {
        return options.stream()
                .filter(o -> o.config().equals(config) && (version == null || o.version().equals(version)))
                .findFirst();
    }

    private void buildDiffGrid() {
        diffGrid.setWidthFull();
        diffGrid.setAllRowsVisible(true);
        diffGrid.addColumn(CheckDelta::category).setHeader("Category").setAutoWidth(true);
        diffGrid.addColumn(d -> shorten(d.question())).setHeader("Question").setFlexGrow(1);
        diffGrid.addColumn(d -> fmt(d.base())).setHeader("Baseline avg").setAutoWidth(true);
        diffGrid.addColumn(d -> fmt(d.compare())).setHeader("Candidate avg").setAutoWidth(true);
        diffGrid.addColumn(d -> signed(d.delta())).setHeader("Δ").setAutoWidth(true);
        diffGrid.setPartNameGenerator(d -> d.delta() > 0.0001 ? "delta-up"
                : d.delta() < -0.0001 ? "delta-down" : null);
        resultsBox.add(diffGrid);
    }

    private void refresh() {
        ConfigOption base = baselineRunField.getValue();
        ConfigOption cand = candidateRunField.getValue();

        List<CheckDelta> deltas = analyticsService.compareConfigs(base, cand);
        CheckAnalyticsService.ComparisonSummary summary = analyticsService.summarizeConfigs(base, cand, deltas);

        renderSummary(summary);

        // overview: overall averaged score and accuracy, baseline vs candidate
        List<MapDataItem> overviewItems = List.of(
                new MapDataItem(Map.of("metric", "Avg score",
                        "baseline", nz(summary.baseScore()), "candidate", nz(summary.candidateScore()))),
                new MapDataItem(Map.of("metric", "Accuracy",
                        "baseline", nz(summary.baseAccuracy()), "candidate", nz(summary.candidateAccuracy()))));
        overviewChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(overviewItems))
                .withCategoryField("metric").withValueFields("baseline", "candidate")));

        // category chart: baseline vs candidate averaged per category
        List<MapDataItem> categoryItems = new ArrayList<>();
        for (CheckAnalyticsService.CategoryScore cs : analyticsService.categoryCompareConfigs(deltas)) {
            categoryItems.add(new MapDataItem(Map.of(
                    "category", cs.category(), "baseline", cs.base(), "candidate", cs.compare())));
        }
        categoryChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(categoryItems))
                .withCategoryField("category").withValueFields("baseline", "candidate")));

        // delta chart + diff grid, optionally regressions-only
        boolean regressionsOnly = Boolean.TRUE.equals(regressionsOnlyField.getValue());
        List<CheckDelta> visible = regressionsOnly
                ? deltas.stream().filter(d -> d.delta() < -0.0001).toList()
                : deltas;

        List<MapDataItem> deltaItems = new ArrayList<>();
        for (CheckDelta d : visible) {
            deltaItems.add(new MapDataItem(Map.of(
                    "question", shorten(d.question()),
                    "up", d.delta() > 0 ? d.delta() : 0.0,
                    "down", d.delta() < 0 ? d.delta() : 0.0)));
        }
        deltaChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(deltaItems))
                .withCategoryField("question").withValueFields("up", "down")));

        diffGrid.setItems(visible);
    }

    private void renderSummary(CheckAnalyticsService.ComparisonSummary s) {
        summaryCards.removeAll();
        summaryCards.add(
                card("Improved", String.valueOf(s.improved()), "delta-up"),
                card("Regressed", String.valueOf(s.regressed()), "delta-down"),
                card("Unchanged", String.valueOf(s.unchanged()), null),
                card("Score", deltaText(s.baseScore(), s.candidateScore()), null),
                card("Accuracy", deltaText(s.baseAccuracy(), s.candidateAccuracy()), null));
    }

    private Span card(String title, String value, String partClass) {
        Span span = new Span(title + ": " + value);
        span.addClassNames("badge");
        if (partClass != null) {
            span.getElement().getThemeList().add(partClass.equals("delta-up") ? "success" : "error");
        }
        return span;
    }

    private static String deltaText(Double base, Double cand) {
        if (base == null || cand == null) {
            return "—";
        }
        double d = cand - base;
        String arrow = d > 0.0001 ? " ▲" : d < -0.0001 ? " ▼" : "";
        return "%.2f → %.2f (%+.2f)%s".formatted(base, cand, d, arrow);
    }

    private static double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    private static String fmt(double v) {
        return "%.2f".formatted(v);
    }

    private static String signed(double v) {
        return "%+.2f".formatted(v);
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_QUESTION_LABEL ? oneLine.substring(0, MAX_QUESTION_LABEL) + "…" : oneLine;
    }
}
