package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckAnalyticsService;
import io.jmix.ai.backend.checks.CheckAnalyticsService.CheckDelta;
import io.jmix.ai.backend.checks.CheckAnalyticsService.ComparisonResult;
import io.jmix.ai.backend.checks.CheckAnalyticsService.ConfigOption;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Route(value = "check-runs/compare", layout = MainView.class)
@ViewController(id = "CheckRun.compare")
@ViewDescriptor(path = "check-run-comparison-view.xml")
public class CheckRunComparisonView extends StandardView {

    private static final int MAX_QUESTION_LABEL = 70;

    @Autowired
    private CheckAnalyticsService analyticsService;
    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private JmixComboBox<ConfigOption> baselineRunField;
    @ViewComponent
    private JmixComboBox<ConfigOption> candidateRunField;
    @ViewComponent
    private JmixCheckbox regressionsOnlyField;
    @ViewComponent
    private Span fieldsHint;
    @ViewComponent
    private Span cohortWarning;
    @ViewComponent
    private HorizontalLayout summaryCards;
    @ViewComponent
    private Chart categoryChart;
    @ViewComponent
    private Chart deltaChart;
    @ViewComponent
    private VerticalLayout resultsBox;

    private final Grid<CheckDelta> diffGrid = new Grid<>(CheckDelta.class, false);
    private List<ConfigOption> configOptions = List.of();
    private boolean updatingCandidateOptions;
    private boolean swapping;

    @Subscribe
    public void onInit(final InitEvent event) {
        configOptions = analyticsService.configOptions();
        baselineRunField.setItems(configOptions);
        baselineRunField.setItemLabelGenerator(this::configOptionLabel);
        candidateRunField.setItemLabelGenerator(this::candidateOptionLabel);
        fieldsHint.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        cohortWarning.getStyle()
                .set("background", "var(--lumo-warning-color-10pct)")
                .set("color", "var(--lumo-warning-text-color)")
                .set("padding", "0.4em 0.8em")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("font-size", "var(--lumo-font-size-s)");
        summaryCards.getStyle().set("flex-wrap", "wrap");

        if (!configOptions.isEmpty()) {
            baselineRunField.setValue(configOptions.getFirst());
            updateCandidateOptions(configOptions.getFirst());
        }

        buildDiffGrid();
        baselineRunField.addValueChangeListener(change -> {
            updatingCandidateOptions = true;
            try {
                updateCandidateOptions(change.getValue());
            } finally {
                updatingCandidateOptions = false;
            }
            if (!swapping) {
                refresh();
            }
        });
        candidateRunField.addValueChangeListener(change -> {
            if (!updatingCandidateOptions && !swapping) {
                refresh();
            }
        });
        regressionsOnlyField.addValueChangeListener(change -> refresh());
        refresh();
    }

    @Subscribe(id = "swapButton", subject = "clickListener")
    public void onSwapButtonClick(final ClickEvent<JmixButton> event) {
        ConfigOption baseline = baselineRunField.getValue();
        ConfigOption candidate = candidateRunField.getValue();
        if (baseline == null || candidate == null) {
            return;
        }
        // suppress the intermediate refreshes: both fields change, one comparison run is enough
        swapping = true;
        try {
            baselineRunField.setValue(candidate);
            candidateRunField.setValue(baseline);
        } finally {
            swapping = false;
        }
        refresh();
    }

    private void updateCandidateOptions(ConfigOption baseline) {
        if (baseline == null) {
            candidateRunField.setItems(List.of());
            candidateRunField.clear();
            return;
        }
        // any configuration can be a candidate; same-cohort ones are more reliable, so they go first
        List<ConfigOption> ordered = configOptions.stream()
                .sorted(Comparator.comparing(option -> !CheckAnalyticsService.canCompare(baseline, option)))
                .toList();
        ConfigOption current = candidateRunField.getValue();
        candidateRunField.setItems(ordered);
        if (current != null && ordered.contains(current)) {
            candidateRunField.setValue(current);
        } else {
            candidateRunField.setValue(ordered.stream()
                    .filter(option -> !option.key().equals(baseline.key()))
                    .filter(option -> CheckAnalyticsService.canCompare(baseline, option))
                    .findFirst()
                    .orElse(ordered.stream()
                            .filter(option -> !option.key().equals(baseline.key()))
                            .findFirst()
                            .orElse(ordered.getFirst())));
        }
    }

    private String candidateOptionLabel(ConfigOption option) {
        String label = configOptionLabel(option);
        return CheckAnalyticsService.canCompare(baselineRunField.getValue(), option)
                ? label
                : messageBundle.formatMessage("configOption.otherCohort", label);
    }

    private void updateCohortWarning(ConfigOption base, ConfigOption candidate) {
        if (base == null || candidate == null || CheckAnalyticsService.canCompare(base, candidate)) {
            cohortWarning.setVisible(false);
            return;
        }
        List<String> differences = new ArrayList<>();
        if (!base.version().equals(candidate.version())) {
            differences.add(messageBundle.formatMessage(
                    "cohortWarning.version", base.version(), candidate.version()));
        }
        if (!base.definitionFingerprint().equals(candidate.definitionFingerprint())) {
            differences.add(messageBundle.getMessage("cohortWarning.definitions"));
        }
        if (!base.evaluatorConfig().equals(candidate.evaluatorConfig())) {
            differences.add(messageBundle.getMessage("cohortWarning.evaluator"));
        }
        if (differences.isEmpty()) {
            differences.add(messageBundle.getMessage("cohortWarning.parameters"));
        }
        cohortWarning.setText(messageBundle.formatMessage(
                "cohortWarning.text", String.join(", ", differences)));
        cohortWarning.setVisible(true);
    }

    private void buildDiffGrid() {
        diffGrid.setWidthFull();
        diffGrid.setAllRowsVisible(true);
        diffGrid.setDetailsVisibleOnClick(false);
        diffGrid.setItemDetailsRenderer(new ComponentRenderer<>(this::details));
        diffGrid.addComponentColumn(this::detailsButton)
                .setHeader(messageBundle.getMessage("grid.details"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        diffGrid.addColumn(CheckDelta::category)
                .setHeader(messageBundle.getMessage("grid.category"))
                .setAutoWidth(true);
        diffGrid.addColumn(delta -> shorten(delta.question()))
                .setHeader(messageBundle.getMessage("grid.question"))
                .setFlexGrow(1);
        diffGrid.addColumn(delta -> formatMetric(delta.base()))
                .setHeader(messageBundle.getMessage("grid.baselineAverage"))
                .setAutoWidth(true);
        diffGrid.addColumn(delta -> formatMetric(delta.compare()))
                .setHeader(messageBundle.getMessage("grid.candidateAverage"))
                .setAutoWidth(true);
        diffGrid.addColumn(delta -> signed(delta.delta()))
                .setHeader(messageBundle.getMessage("grid.delta"))
                .setAutoWidth(true);
        resultsBox.add(diffGrid);
    }

    private Button detailsButton(CheckDelta delta) {
        Button button = new Button(messageBundle.getMessage("details.action"));
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        button.addClickListener(click -> diffGrid.setDetailsVisible(delta, !diffGrid.isDetailsVisible(delta)));
        return button;
    }

    private Component details(CheckDelta delta) {
        VerticalLayout content = new VerticalLayout();
        content.setPadding(true);
        content.setSpacing(true);
        content.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");

        Span explanation = new Span(messageBundle.getMessage("details.aggregationNote"));
        explanation.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        content.add(
                explanation,
                detailSection(messageBundle.getMessage("details.question"), delta.question()),
                detailSection(messageBundle.getMessage("details.referenceAnswer"), delta.referenceAnswer()),
                detailSection(messageBundle.getMessage("details.baselineActualAnswer"), delta.baselineActualAnswer()),
                detailSection(messageBundle.getMessage("details.candidateActualAnswer"), delta.candidateActualAnswer()));
        return content;
    }

    private Component detailSection(String label, String value) {
        Span heading = new Span(label);
        heading.getStyle().set("font-weight", "700");
        Div text = new Div();
        text.setText(value);
        text.getStyle()
                .set("white-space", "pre-wrap")
                .set("overflow-wrap", "anywhere");
        Div section = new Div(heading, text);
        section.setWidthFull();
        return section;
    }

    private void refresh() {
        ConfigOption base = baselineRunField.getValue();
        ConfigOption candidate = candidateRunField.getValue();
        updateCohortWarning(base, candidate);
        ComparisonResult comparison = analyticsService.compareConfigs(base, candidate);
        CheckAnalyticsService.ComparisonSummary summary =
                analyticsService.summarizeConfigs(comparison);

        renderSummary(summary);

        List<MapDataItem> categoryItems = new ArrayList<>();
        for (CheckAnalyticsService.CategoryScore category :
                analyticsService.categoryCompareConfigs(comparison.deltas())) {
            categoryItems.add(new MapDataItem(Map.of(
                    "category", category.category(),
                    "baseline", category.base(),
                    "candidate", category.compare())));
        }
        categoryChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(categoryItems))
                .withCategoryField("category")
                .withValueFields("baseline", "candidate")));

        boolean regressionsOnly = Boolean.TRUE.equals(regressionsOnlyField.getValue());
        List<CheckDelta> visible = regressionsOnly
                ? comparison.deltas().stream().filter(delta -> delta.delta() < -0.0001).toList()
                : comparison.deltas();

        List<MapDataItem> deltaItems = new ArrayList<>();
        for (CheckDelta delta : visible) {
            deltaItems.add(new MapDataItem(Map.of(
                    "question", shorten(delta.question()),
                    "improved", delta.delta() > 0 ? delta.delta() : 0.0,
                    "regressed", delta.delta() < 0 ? delta.delta() : 0.0)));
        }
        deltaChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(deltaItems))
                .withCategoryField("question")
                .withValueFields("improved", "regressed")));

        diffGrid.setItems(visible);
    }

    private void renderSummary(CheckAnalyticsService.ComparisonSummary summary) {
        summaryCards.removeAll();
        summaryCards.add(
                card(messageBundle.getMessage("summary.common"), String.valueOf(summary.common()), null),
                card(messageBundle.getMessage("summary.improved"), String.valueOf(summary.improved()), "success"),
                card(messageBundle.getMessage("summary.regressed"), String.valueOf(summary.regressed()), "error"),
                card(messageBundle.getMessage("summary.unchanged"), String.valueOf(summary.unchanged()), null),
                card(messageBundle.getMessage("summary.baselineOnly"), String.valueOf(summary.baselineOnly()), "warning"),
                card(messageBundle.getMessage("summary.candidateOnly"), String.valueOf(summary.candidateOnly()), "warning"),
                card(messageBundle.getMessage("summary.score"),
                        deltaText(summary.baseScore(), summary.candidateScore()), null),
                card(messageBundle.getMessage("summary.accuracy"),
                        deltaText(summary.baseAccuracy(), summary.candidateAccuracy()), null));
    }

    private VerticalLayout card(String title, String value, String status) {
        Span titleSpan = new Span(title);
        titleSpan.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        Span valueSpan = new Span(value);
        valueSpan.getStyle()
                .set("font-size", "var(--lumo-font-size-l)")
                .set("font-weight", "700")
                .set("font-variant-numeric", "tabular-nums");

        VerticalLayout card = new VerticalLayout(titleSpan, valueSpan);
        card.setPadding(true);
        card.setSpacing(false);
        card.setWidth("13em");
        card.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-l)");
        if (status != null) {
            card.getStyle().set("border-left", "4px solid var(--lumo-%s-color)".formatted(status));
        }
        return card;
    }

    private String configOptionLabel(ConfigOption option) {
        String description = !option.description().isBlank()
                ? shorten(option.description())
                : messageBundle.getMessage("config.unlabeled");
        String messageKey = option.runCount() == 1 ? "configOption.oneRun" : "configOption.manyRuns";
        return messageBundle.formatMessage(
                messageKey, option.version(), description, option.fingerprint(), option.runCount());
    }

    private static String deltaText(Double base, Double candidate) {
        if (base == null || candidate == null) {
            return "—";
        }
        double delta = candidate - base;
        String arrow = delta > 0.0001 ? " ▲" : delta < -0.0001 ? " ▼" : "";
        return "%.2f → %.2f (%+.2f)%s".formatted(base, candidate, delta, arrow);
    }

    private static String formatMetric(double value) {
        return "%.2f".formatted(value);
    }

    private static String signed(double value) {
        return "%+.2f".formatted(value);
    }

    private static String shorten(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_QUESTION_LABEL
                ? oneLine.substring(0, MAX_QUESTION_LABEL) + "…"
                : oneLine;
    }
}
