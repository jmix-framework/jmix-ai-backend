package io.jmix.ai.backend.view.checkrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckRunComparisonService;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.MessageBundle;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Route(value = "check-runs/compare", layout = MainView.class)
@ViewController(id = "CheckRun.compare")
@ViewDescriptor(path = "check-run-comparison-view.xml")
public class CheckRunComparisonView extends StandardView {

    private static final DateTimeFormatter RUN_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private CheckRunComparisonService comparisonService;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private ObjectMapper objectMapper;

    @ViewComponent
    private EntityComboBox<CheckRun> baselineRunField;
    @ViewComponent
    private EntityComboBox<CheckRun> candidateRunField;
    @ViewComponent
    private HorizontalLayout summaryCards;
    @ViewComponent
    private VerticalLayout historyBox;
    @ViewComponent
    private HorizontalLayout chartsBox;
    @ViewComponent
    private VerticalLayout resultsBox;
    @ViewComponent
    private Checkbox regressionsOnlyField;
    @ViewComponent
    private Checkbox historyComparableOnlyField;
    @ViewComponent
    private JmixComboBox<String> categoryFilterField;
    @ViewComponent
    private JmixComboBox<String> historyModelFilterField;
    @ViewComponent
    private VerticalLayout metaBox;
    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private JmixButton compareButton;
    @ViewComponent
    private CollectionContainer<CheckRun> successfulRunsDc;

    private Grid<CheckRunComparisonService.ComparisonRow> comparisonGrid;
    private List<CheckRunComparisonService.ComparisonRow> currentRows = List.of();

    @Subscribe
    public void onInit(InitEvent event) {
        baselineRunField.setItemLabelGenerator(this::formatRunLabel);
        candidateRunField.setItemLabelGenerator(this::formatRunLabel);
        baselineRunField.setPlaceholder(messageBundle.getMessage("checkRunComparisonView.baselineRun.placeholder"));
        candidateRunField.setPlaceholder(messageBundle.getMessage("checkRunComparisonView.candidateRun.placeholder"));
        categoryFilterField.setPlaceholder(messageBundle.getMessage("checkRunComparisonView.categoryFilter.placeholder"));
        baselineRunField.getElement().setProperty("title", messageBundle.getMessage("checkRunComparisonView.baselineRun.tooltip"));
        candidateRunField.getElement().setProperty("title", messageBundle.getMessage("checkRunComparisonView.candidateRun.tooltip"));
        regressionsOnlyField.getElement().setProperty("title", messageBundle.getMessage("checkRunComparisonView.regressionsOnly.tooltip"));
        categoryFilterField.getElement().setProperty("title", messageBundle.getMessage("checkRunComparisonView.categoryFilter.tooltip"));
        compareButton.getElement().setProperty("title", messageBundle.getMessage("checkRunComparisonView.compare.tooltip"));
        historyComparableOnlyField.setValue(Boolean.TRUE);
        historyModelFilterField.setPlaceholder(messageBundle.getMessage("checkRunComparisonView.historyModelFilter.placeholder"));
        historyComparableOnlyField.getElement().setProperty("title", messageBundle.getMessage("checkRunComparisonView.historyComparableOnly.tooltip"));
        historyModelFilterField.getElement().setProperty("title", messageBundle.getMessage("checkRunComparisonView.historyModelFilter.tooltip"));
    }

    @Subscribe
    public void onBeforeShow(BeforeShowEvent event) {
        updateHistoryModelFilter();
        List<CheckRun> runs = successfulRunsDc.getItems();
        if (runs.size() >= 2) {
            baselineRunField.setValue(runs.get(1));
            candidateRunField.setValue(runs.get(0));
            renderComparison();
        } else if (runs.size() == 1) {
            baselineRunField.setValue(runs.getFirst());
        }
        renderHistorySection();
    }

    @Subscribe(id = "compareButton", subject = "clickListener")
    public void onCompareButtonClick(ClickEvent<JmixButton> event) {
        renderComparison();
    }

    @Subscribe("baselineRunField")
    public void onBaselineRunFieldValueChange(com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<EntityComboBox<CheckRun>, CheckRun> event) {
        if (event.isFromClient()) {
            renderComparison();
            renderHistorySection();
        }
    }

    @Subscribe("candidateRunField")
    public void onCandidateRunFieldValueChange(com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<EntityComboBox<CheckRun>, CheckRun> event) {
        if (event.isFromClient()) {
            renderComparison();
            renderHistorySection();
        }
    }

    private void renderComparison() {
        CheckRun baselineRun = baselineRunField.getValue();
        CheckRun candidateRun = candidateRunField.getValue();
        if (baselineRun == null || candidateRun == null) {
            return;
        }

        CheckRunComparisonService.ComparisonReport report = comparisonService.compare(
                baselineRun.getId(),
                candidateRun.getId()
        );

        renderMeta(baselineRun, candidateRun, report.summary());
        renderSummary(report.summary());
        renderCharts(report);
        renderCategoryFilter(report.categorySummaries());
        currentRows = report.rows();
        renderGrid(currentRows);
        renderHistorySection();
    }

    private void renderHistorySection() {
        historyBox.removeAll();
        List<CheckRun> runs = getHistoryRuns();
        if (runs.isEmpty()) {
            Span emptyState = new Span(messageBundle.getMessage("checkRunComparisonView.history.empty"));
            emptyState.getStyle().set("color", "var(--lumo-secondary-text-color)");
            historyBox.add(emptyState);
            return;
        }
        historyBox.add(createHistoryNote(runs), createHistoryChart(runs));
    }

    private void updateHistoryModelFilter() {
        String currentValue = historyModelFilterField.getValue();
        List<String> modelNames = successfulRunsDc.getItems().stream()
                .filter(run -> Boolean.TRUE.equals(run.getGoldenOnly()))
                .map(CheckRun::getAnswerModel)
                .filter(Objects::nonNull)
                .filter(model -> !model.isBlank())
                .distinct()
                .sorted()
                .toList();
        historyModelFilterField.setItems(modelNames);
        if (currentValue != null && modelNames.contains(currentValue)) {
            historyModelFilterField.setValue(currentValue);
        } else {
            historyModelFilterField.clear();
        }
    }

    private List<CheckRun> getHistoryRuns() {
        Stream<CheckRun> stream = successfulRunsDc.getItems().stream()
                .filter(run -> Boolean.TRUE.equals(run.getGoldenOnly()));

        String modelFilter = historyModelFilterField.getValue();
        if (modelFilter != null && !modelFilter.isBlank()) {
            stream = stream.filter(run -> Objects.equals(run.getAnswerModel(), modelFilter));
        }

        if (Boolean.TRUE.equals(historyComparableOnlyField.getValue())) {
            CheckRun anchor = Optional.ofNullable(candidateRunField.getValue())
                    .or(() -> Optional.ofNullable(baselineRunField.getValue()))
                    .or(() -> successfulRunsDc.getItems().stream().findFirst())
                    .orElse(null);
            if (anchor != null) {
                stream = stream.filter(run -> isComparableToAnchor(run, anchor));
            }
        }

        return stream
                .sorted((left, right) -> {
                    if (left.getCreatedDate() == null && right.getCreatedDate() == null) {
                        return left.getId().compareTo(right.getId());
                    }
                    if (left.getCreatedDate() == null) {
                        return -1;
                    }
                    if (right.getCreatedDate() == null) {
                        return 1;
                    }
                    int byDate = left.getCreatedDate().compareTo(right.getCreatedDate());
                    return byDate != 0 ? byDate : left.getId().compareTo(right.getId());
                })
                .toList();
    }

    private boolean isComparableToAnchor(CheckRun run, CheckRun anchor) {
        return Objects.equals(Boolean.TRUE.equals(run.getGoldenOnly()), Boolean.TRUE.equals(anchor.getGoldenOnly()))
                && Objects.equals(run.getDatasetVersion(), anchor.getDatasetVersion())
                && Objects.equals(run.getRetrievalProfile(), anchor.getRetrievalProfile())
                && Objects.equals(run.getPromptRevision(), anchor.getPromptRevision())
                && Objects.equals(run.getKnowledgeSnapshot(), anchor.getKnowledgeSnapshot())
                && Objects.equals(run.getEvaluatorModel(), anchor.getEvaluatorModel())
                && Objects.equals(run.getEvaluatorEndpoint(), anchor.getEvaluatorEndpoint());
    }

    private Component createHistoryNote(List<CheckRun> runs) {
        CheckRun anchor = Optional.ofNullable(candidateRunField.getValue())
                .or(() -> Optional.ofNullable(baselineRunField.getValue()))
                .orElse(null);

        String anchorText = anchor != null
                ? formatRunLabel(anchor)
                : messageBundle.getMessage("checkRunComparisonView.history.anchor.latest");
        String noteText = Boolean.TRUE.equals(historyComparableOnlyField.getValue())
                ? messageBundle.formatMessage("checkRunComparisonView.history.note.comparable", Integer.toString(runs.size()), anchorText)
                : messageBundle.formatMessage("checkRunComparisonView.history.note.all", Integer.toString(runs.size()));

        Span note = new Span(noteText);
        note.getStyle()
                .set("display", "block")
                .set("padding", "0.8rem 1rem")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("color", "var(--lumo-secondary-text-color)");
        return note;
    }

    private Component createHistoryChart(List<CheckRun> runs) {
        Chart chart = uiComponents.create(Chart.class);
        chart.setWidthFull();
        chart.setHeight("24em");

        List<String> xAxisLabels = runs.stream()
                .map(run -> run.getCreatedDate() != null ? RUN_LABEL_FORMATTER.format(run.getCreatedDate()) : "n/a")
                .toList();
        List<String> modelNames = runs.stream()
                .map(run -> nullToDash(run.getAnswerModel()))
                .distinct()
                .toList();

        List<Map<String, Object>> series = new ArrayList<>();
        for (String modelName : modelNames) {
            List<Object> data = new ArrayList<>();
            for (CheckRun run : runs) {
                if (Objects.equals(nullToDash(run.getAnswerModel()), modelName)) {
                    data.add(roundToThreeDecimals(run.getScore()));
                } else {
                    data.add(null);
                }
            }
            series.add(Map.of(
                    "name", modelName,
                    "type", "line",
                    "connectNulls", false,
                    "showSymbol", true,
                    "symbolSize", 8,
                    "data", data
            ));
        }

        chart.setNativeJson(toJson(Map.of(
                "tooltip", Map.of("trigger", "axis"),
                "legend", Map.of("top", "bottom"),
                "grid", Map.of("left", "4%", "right", "4%", "bottom", "15%", "containLabel", true),
                "xAxis", Map.of("type", "category", "data", xAxisLabels),
                "yAxis", Map.of("type", "value", "min", 0, "max", 1),
                "series", series
        )));
        return wrapChart(messageBundle.getMessage("checkRunComparisonView.chart.history"), chart);
    }

    private void renderCategoryFilter(List<CheckRunComparisonService.CategorySummary> categorySummaries) {
        String currentValue = categoryFilterField.getValue();
        List<String> categories = categorySummaries.stream()
                .map(CheckRunComparisonService.CategorySummary::category)
                .toList();
        categoryFilterField.setItems(categories);
        if (currentValue != null && categories.contains(currentValue)) {
            categoryFilterField.setValue(currentValue);
        } else {
            categoryFilterField.clear();
        }
    }

    private void renderMeta(CheckRun baselineRun,
                            CheckRun candidateRun,
                            CheckRunComparisonService.ComparisonSummary summary) {
        metaBox.removeAll();

        HorizontalLayout runCards = new HorizontalLayout();
        runCards.addClassName("comparison-run-cards");
        runCards.setWidthFull();
        runCards.setSpacing(true);
        runCards.add(
                createRunCard(
                        messageBundle.getMessage("checkRunComparisonView.baselineRunCard"),
                        baselineRun,
                        "var(--lumo-primary-color)"
                ),
                createRunCard(
                        messageBundle.getMessage("checkRunComparisonView.candidateRunCard"),
                        candidateRun,
                        "var(--lumo-success-color)"
                )
        );
        metaBox.add(runCards);
        metaBox.add(createValidityBadge(baselineRun, candidateRun));
        metaBox.add(createJudgeNote(baselineRun, candidateRun, summary));
    }

    private void renderSummary(CheckRunComparisonService.ComparisonSummary summary) {
        summaryCards.removeAll();
        summaryCards.add(
                createSummaryCard(messageBundle.getMessage("checkRunComparisonView.summary.questions"), Integer.toString(summary.totalQuestions())),
                createSummaryCard(messageBundle.getMessage("checkRunComparisonView.summary.baselineAvg"), formatScore(summary.baselineAverage())),
                createSummaryCard(messageBundle.getMessage("checkRunComparisonView.summary.candidateAvg"), formatScore(summary.candidateAverage())),
                createSummaryCard(messageBundle.getMessage("checkRunComparisonView.summary.improved"), Integer.toString(summary.improvedCount())),
                createSummaryCard(messageBundle.getMessage("checkRunComparisonView.summary.regressed"), Integer.toString(summary.regressedCount())),
                createSummaryCard(messageBundle.getMessage("checkRunComparisonView.summary.avgDelta"), formatDelta(summary.averageDelta()))
        );
    }

    private Component createSummaryCard(String title, String value) {
        VerticalLayout card = new VerticalLayout();
        card.addClassName("comparison-summary-card");
        card.setPadding(true);
        card.setSpacing(false);
        card.setWidthFull();
        card.getStyle()
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("background", "linear-gradient(180deg, var(--lumo-base-color), var(--lumo-contrast-5pct))");

        Span titleSpan = new Span(title);
        titleSpan.getStyle().set("color", "var(--lumo-secondary-text-color)");

        H3 valueSpan = new H3(value);
        valueSpan.getStyle().set("margin", "0");

        card.add(titleSpan, valueSpan);
        return card;
    }

    private void renderCharts(CheckRunComparisonService.ComparisonReport report) {
        chartsBox.removeAll();
        chartsBox.add(
                createTrendChart(report.summary()),
                createCategoryComparisonChart(report.categorySummaries()),
                createCategoryDeltaChart(report.categorySummaries())
        );
    }

    private Component createTrendChart(CheckRunComparisonService.ComparisonSummary summary) {
        Chart chart = uiComponents.create(Chart.class);
        chart.setWidthFull();
        chart.setHeight("22em");
        chart.setNativeJson(toJson(Map.of(
                "tooltip", Map.of(
                        "trigger", "item",
                        "formatter", "{b}: {c}"
                ),
                "legend", Map.of("top", "bottom"),
                "series", List.of(Map.of(
                        "type", "pie",
                        "radius", List.of("38%", "68%"),
                        "label", Map.of("show", false),
                        "labelLine", Map.of("show", false),
                        "data", List.of(
                                Map.of(
                                        "name", "Improved",
                                        "value", summary.improvedCount(),
                                        "itemStyle", Map.of("color", "#6fbe73")
                                ),
                                Map.of(
                                        "name", "Regressed",
                                        "value", summary.regressedCount(),
                                        "itemStyle", Map.of("color", "#d66a5e")
                                ),
                                Map.of(
                                        "name", "Unchanged",
                                        "value", summary.unchangedCount(),
                                        "itemStyle", Map.of("color", "#f1c75b")
                                )
                        )
                ))
        )));
        return wrapChart(messageBundle.getMessage("checkRunComparisonView.chart.outcomeSplit"), chart);
    }

    private Component createCategoryDeltaChart(List<CheckRunComparisonService.CategorySummary> categories) {
        Chart chart = uiComponents.create(Chart.class);
        chart.setWidthFull();
        chart.setHeight("22em");

        List<String> categoryNames = categories.stream()
                .map(CheckRunComparisonService.CategorySummary::category)
                .toList();
        List<Double> deltas = categories.stream()
                .map(summary -> roundToThreeDecimals(summary.delta()))
                .toList();

        chart.setNativeJson(toJson(Map.of(
                "tooltip", Map.of(
                        "trigger", "item",
                        "formatter", "{b}: {c}"
                ),
                "xAxis", Map.of("type", "category", "data", categoryNames),
                "yAxis", Map.of("type", "value"),
                "series", List.of(Map.of(
                        "name", "Score delta",
                        "type", "bar",
                        "data", deltas
                ))
        )));
        return wrapChart(messageBundle.getMessage("checkRunComparisonView.chart.categoryDelta"), chart);
    }

    private Component createCategoryComparisonChart(List<CheckRunComparisonService.CategorySummary> categories) {
        Chart chart = uiComponents.create(Chart.class);
        chart.setWidthFull();
        chart.setHeight("22em");

        List<String> categoryNames = categories.stream()
                .map(CheckRunComparisonService.CategorySummary::category)
                .toList();
        List<Double> baseline = categories.stream()
                .map(summary -> summary.baselineAverage() != null ? summary.baselineAverage() : 0d)
                .toList();
        List<Double> candidate = categories.stream()
                .map(summary -> summary.candidateAverage() != null ? summary.candidateAverage() : 0d)
                .toList();

        chart.setNativeJson(toJson(Map.of(
                "tooltip", Map.of("trigger", "axis"),
                "legend", Map.of("data", List.of("Baseline", "Candidate")),
                "xAxis", Map.of("type", "category", "data", categoryNames),
                "yAxis", Map.of("type", "value"),
                "series", List.of(
                        Map.of("name", "Baseline", "type", "bar", "data", baseline),
                        Map.of("name", "Candidate", "type", "bar", "data", candidate)
                )
        )));
        return wrapChart(messageBundle.getMessage("checkRunComparisonView.chart.categoryComparison"), chart);
    }

    private Component wrapChart(String title, Chart chart) {
        VerticalLayout wrapper = new VerticalLayout();
        wrapper.addClassName("comparison-chart-card");
        wrapper.setPadding(false);
        wrapper.setSpacing(false);
        wrapper.setWidthFull();
        wrapper.getStyle()
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "0.75rem");
        H4 heading = new H4(title);
        heading.addClassName("comparison-chart-title");
        wrapper.add(heading, chart);
        return wrapper;
    }

    private Grid<CheckRunComparisonService.ComparisonRow> buildGrid() {
        Grid<CheckRunComparisonService.ComparisonRow> grid = new Grid<>();
        grid.addClassName("comparison-grid");
        grid.setWidthFull();
        grid.setHeight("42em");
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_WRAP_CELL_CONTENT);

        grid.addColumn(CheckRunComparisonService.ComparisonRow::category)
                .setHeader(messageBundle.getMessage("checkRunComparisonView.grid.category"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(CheckRunComparisonService.ComparisonRow::question)
                .setHeader(messageBundle.getMessage("checkRunComparisonView.grid.question"))
                .setFlexGrow(1)
                .setAutoWidth(true);
        grid.addColumn(row -> formatScore(row.baselineScore()))
                .setHeader(messageBundle.getMessage("checkRunComparisonView.grid.baseline"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(row -> formatScore(row.candidateScore()))
                .setHeader(messageBundle.getMessage("checkRunComparisonView.grid.candidate"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(row -> formatDelta(row.delta()))
                .setHeader(messageBundle.getMessage("checkRunComparisonView.grid.delta"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(new ComponentRenderer<>(this::createTrendBadge))
                .setHeader(messageBundle.getMessage("checkRunComparisonView.grid.trend"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(new ComponentRenderer<>(this::createLinks))
                .setHeader(messageBundle.getMessage("checkRunComparisonView.grid.links"))
                .setAutoWidth(true)
                .setFlexGrow(0);

        grid.setItemDetailsRenderer(new ComponentRenderer<>(this::createDetails));
        grid.addItemClickListener(event -> grid.setDetailsVisible(event.getItem(), !grid.isDetailsVisible(event.getItem())));
        return grid;
    }

    private Component createTrendBadge(CheckRunComparisonService.ComparisonRow row) {
        Span badge = new Span(getTrend(row.delta()));
        String backgroundColor = "var(--lumo-contrast-10pct)";
        if (row.delta() != null && row.delta() > 0.0001d) {
            backgroundColor = "var(--lumo-success-color-10pct)";
        } else if (row.delta() != null && row.delta() < -0.0001d) {
            backgroundColor = "var(--lumo-error-color-10pct)";
        }
        badge.getStyle()
                .set("border-radius", "999px")
                .set("padding", "0.2rem 0.6rem")
                .set("font-weight", "600")
                .set("background", backgroundColor);
        return badge;
    }

    private Component createDetails(CheckRunComparisonService.ComparisonRow row) {
        SplitLayout split = new SplitLayout();
        split.setWidthFull();
        split.setSplitterPosition(50);
        split.addToPrimary(createAnswerPanel(
                messageBundle.getMessage("checkRunComparisonView.details.baselineAnswer"),
                row.baselineAnswer(),
                row.baselineLog()
        ));
        split.addToSecondary(createAnswerPanel(
                messageBundle.getMessage("checkRunComparisonView.details.candidateAnswer"),
                row.candidateAnswer(),
                row.candidateLog()
        ));
        return split;
    }

    private Component createLinks(CheckRunComparisonService.ComparisonRow row) {
        HorizontalLayout layout = new HorizontalLayout();
        layout.setSpacing(true);
        layout.setPadding(false);
        if (row.baselineCheckId() != null) {
            layout.add(createCheckLink("B", row.baselineCheckId().toString()));
        }
        if (row.candidateCheckId() != null) {
            layout.add(createCheckLink("C", row.candidateCheckId().toString()));
        }
        return layout;
    }

    private Component createCheckLink(String label, String checkId) {
        Anchor anchor = new Anchor("/checks/" + checkId + "?mode=readonly", label);
        anchor.setTarget("_blank");
        return anchor;
    }

    private Component createAnswerPanel(String title, String answer, String log) {
        VerticalLayout panel = new VerticalLayout();
        panel.addClassName("comparison-answer-panel");
        panel.setPadding(false);
        panel.setSpacing(false);
        panel.add(new H4(title));
        panel.add(createScrollablePre(answer));
        panel.add(new Span(messageBundle.getMessage("checkRunComparisonView.details.log")));
        panel.add(createScrollablePre(log));
        return panel;
    }

    private Component createRunCard(String title, CheckRun run, String accentColor) {
        VerticalLayout card = new VerticalLayout();
        card.addClassName("comparison-run-card");
        card.setPadding(true);
        card.setSpacing(false);
        card.setWidthFull();
        card.getStyle()
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("border-top", "0.35rem solid " + accentColor)
                .set("background", "linear-gradient(180deg, var(--lumo-base-color), var(--lumo-contrast-5pct))");

        H4 header = new H4(title);
        header.getStyle().set("margin", "0 0 0.5rem 0");
        card.add(
                header,
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.created"), run.getCreatedDate() != null ? RUN_LABEL_FORMATTER.format(run.getCreatedDate()) : "-"),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.score"), formatScore(run.getScore())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.subset"), formatSubset(run.getGoldenOnly())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.answerModel"), nullToDash(run.getAnswerModel())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.experimentKey"), nullToDash(run.getExperimentKey())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.retrievalProfile"), nullToDash(run.getRetrievalProfile())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.promptRevision"), nullToDash(run.getPromptRevision())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.knowledgeSnapshot"), nullToDash(run.getKnowledgeSnapshot())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.dataset"), nullToDash(run.getDatasetVersion())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.evaluatorModel"), nullToDash(run.getEvaluatorModel())),
                createMetaRow(messageBundle.getMessage("checkRunComparisonView.meta.evaluatorEndpoint"), nullToDash(run.getEvaluatorEndpoint()))
        );
        return card;
    }

    private Component createMetaRow(String label, String value) {
        HorizontalLayout row = new HorizontalLayout();
        row.setPadding(false);
        row.setSpacing(true);
        row.setWidthFull();

        Span labelSpan = new Span(label);
        labelSpan.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("min-width", "9rem");
        Span valueSpan = new Span(value);
        valueSpan.getStyle().set("font-weight", "600");

        row.add(labelSpan, valueSpan);
        row.expand(valueSpan);
        return row;
    }

    private Component createJudgeNote(CheckRun baselineRun,
                                      CheckRun candidateRun,
                                      CheckRunComparisonService.ComparisonSummary summary) {
        boolean sameSubset = Objects.equals(Boolean.TRUE.equals(baselineRun.getGoldenOnly()), Boolean.TRUE.equals(candidateRun.getGoldenOnly()));
        boolean sameDataset = Objects.equals(baselineRun.getDatasetVersion(), candidateRun.getDatasetVersion());
        boolean sameRetrievalProfile = Objects.equals(baselineRun.getRetrievalProfile(), candidateRun.getRetrievalProfile());
        boolean samePromptRevision = Objects.equals(baselineRun.getPromptRevision(), candidateRun.getPromptRevision());
        boolean sameKnowledgeSnapshot = Objects.equals(baselineRun.getKnowledgeSnapshot(), candidateRun.getKnowledgeSnapshot());
        boolean sameEvaluatorModel = Objects.equals(baselineRun.getEvaluatorModel(), candidateRun.getEvaluatorModel());
        boolean sameEvaluatorEndpoint = Objects.equals(baselineRun.getEvaluatorEndpoint(), candidateRun.getEvaluatorEndpoint());
        boolean fullyComparable = isFullyComparable(
                sameSubset,
                sameDataset,
                sameRetrievalProfile,
                samePromptRevision,
                sameKnowledgeSnapshot,
                sameEvaluatorModel,
                sameEvaluatorEndpoint
        );

        String messageKey = fullyComparable
                ? "checkRunComparisonView.note.sameJudge"
                : "checkRunComparisonView.note.changedJudge";

        String message = fullyComparable
                ? messageBundle.formatMessage(
                messageKey,
                formatSubset(baselineRun.getGoldenOnly()),
                nullToDash(baselineRun.getDatasetVersion()),
                nullToDash(baselineRun.getRetrievalProfile()),
                nullToDash(baselineRun.getPromptRevision()),
                nullToDash(baselineRun.getKnowledgeSnapshot()),
                nullToDash(baselineRun.getEvaluatorModel()),
                nullToDash(baselineRun.getEvaluatorEndpoint()),
                formatDelta(summary.averageDelta())
        )
                : messageBundle.formatMessage(
                messageKey,
                formatSubset(baselineRun.getGoldenOnly()),
                nullToDash(baselineRun.getDatasetVersion()),
                nullToDash(baselineRun.getRetrievalProfile()),
                nullToDash(baselineRun.getPromptRevision()),
                nullToDash(baselineRun.getKnowledgeSnapshot()),
                nullToDash(baselineRun.getEvaluatorModel()),
                nullToDash(baselineRun.getEvaluatorEndpoint()),
                formatSubset(candidateRun.getGoldenOnly()),
                nullToDash(candidateRun.getDatasetVersion()),
                nullToDash(candidateRun.getRetrievalProfile()),
                nullToDash(candidateRun.getPromptRevision()),
                nullToDash(candidateRun.getKnowledgeSnapshot()),
                nullToDash(candidateRun.getEvaluatorModel()),
                nullToDash(candidateRun.getEvaluatorEndpoint()),
                formatDelta(summary.averageDelta())
        );

        Span note = new Span(message);
        note.addClassName("comparison-note");
        note.getStyle()
                .set("display", "block")
                .set("padding", "0.8rem 1rem")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("background", fullyComparable
                        ? "var(--lumo-success-color-10pct)"
                        : "var(--lumo-warning-color-10pct)");
        return note;
    }

    private Component createValidityBadge(CheckRun baselineRun, CheckRun candidateRun) {
        boolean sameSubset = Objects.equals(Boolean.TRUE.equals(baselineRun.getGoldenOnly()), Boolean.TRUE.equals(candidateRun.getGoldenOnly()));
        boolean sameDataset = Objects.equals(baselineRun.getDatasetVersion(), candidateRun.getDatasetVersion());
        boolean sameRetrievalProfile = Objects.equals(baselineRun.getRetrievalProfile(), candidateRun.getRetrievalProfile());
        boolean samePromptRevision = Objects.equals(baselineRun.getPromptRevision(), candidateRun.getPromptRevision());
        boolean sameKnowledgeSnapshot = Objects.equals(baselineRun.getKnowledgeSnapshot(), candidateRun.getKnowledgeSnapshot());
        boolean sameEvaluatorModel = Objects.equals(baselineRun.getEvaluatorModel(), candidateRun.getEvaluatorModel());
        boolean sameEvaluatorEndpoint = Objects.equals(baselineRun.getEvaluatorEndpoint(), candidateRun.getEvaluatorEndpoint());
        boolean fullyComparable = isFullyComparable(
                sameSubset,
                sameDataset,
                sameRetrievalProfile,
                samePromptRevision,
                sameKnowledgeSnapshot,
                sameEvaluatorModel,
                sameEvaluatorEndpoint
        );

        String label = fullyComparable
                ? messageBundle.getMessage("checkRunComparisonView.validity.strict")
                : messageBundle.getMessage("checkRunComparisonView.validity.caution");
        String reason = buildValidityReason(
                sameSubset,
                sameDataset,
                sameRetrievalProfile,
                samePromptRevision,
                sameKnowledgeSnapshot,
                sameEvaluatorModel,
                sameEvaluatorEndpoint
        );

        HorizontalLayout layout = new HorizontalLayout();
        layout.addClassName("comparison-validity");
        layout.setWidthFull();
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.getStyle().set("align-items", "center");

        Span badge = new Span(label);
        badge.getStyle()
                .set("border-radius", "999px")
                .set("padding", "0.25rem 0.8rem")
                .set("font-weight", "700")
                .set("background", fullyComparable
                        ? "var(--lumo-success-color-10pct)"
                        : "var(--lumo-warning-color-10pct)");

        Span text = new Span(reason);
        text.getStyle().set("color", "var(--lumo-secondary-text-color)");

        layout.add(badge, text);
        layout.expand(text);
        return layout;
    }

    private String buildValidityReason(boolean sameSubset,
                                       boolean sameDataset,
                                       boolean sameRetrievalProfile,
                                       boolean samePromptRevision,
                                       boolean sameKnowledgeSnapshot,
                                       boolean sameEvaluatorModel,
                                       boolean sameEvaluatorEndpoint) {
        if (sameSubset && sameDataset && sameRetrievalProfile && samePromptRevision
                && sameKnowledgeSnapshot && sameEvaluatorModel && sameEvaluatorEndpoint) {
            return messageBundle.getMessage("checkRunComparisonView.validity.reason.strict");
        }

        StringBuilder reason = new StringBuilder();
        if (!sameSubset) {
            reason.append(messageBundle.getMessage("checkRunComparisonView.validity.reason.subset"));
        }
        if (!sameDataset) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append(messageBundle.getMessage("checkRunComparisonView.validity.reason.dataset"));
        }
        if (!sameRetrievalProfile) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append(messageBundle.getMessage("checkRunComparisonView.validity.reason.retrieval"));
        }
        if (!samePromptRevision) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append(messageBundle.getMessage("checkRunComparisonView.validity.reason.prompt"));
        }
        if (!sameKnowledgeSnapshot) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append(messageBundle.getMessage("checkRunComparisonView.validity.reason.knowledge"));
        }
        if (!sameEvaluatorModel) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append(messageBundle.getMessage("checkRunComparisonView.validity.reason.model"));
        }
        if (!sameEvaluatorEndpoint) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append(messageBundle.getMessage("checkRunComparisonView.validity.reason.endpoint"));
        }
        return reason.toString();
    }

    private boolean isFullyComparable(boolean sameSubset,
                                      boolean sameDataset,
                                      boolean sameRetrievalProfile,
                                      boolean samePromptRevision,
                                      boolean sameKnowledgeSnapshot,
                                      boolean sameEvaluatorModel,
                                      boolean sameEvaluatorEndpoint) {
        return sameSubset && sameDataset && sameRetrievalProfile && samePromptRevision
                && sameKnowledgeSnapshot && sameEvaluatorModel && sameEvaluatorEndpoint;
    }

    private Component createScrollablePre(String text) {
        Pre pre = new Pre(text != null ? text : "");
        pre.getStyle()
                .set("white-space", "pre-wrap")
                .set("margin", "0")
                .set("font-size", "var(--lumo-font-size-s)");
        Scroller scroller = new Scroller(pre);
        scroller.setHeight("10em");
        scroller.setWidthFull();
        return scroller;
    }

    private String formatRunLabel(CheckRun checkRun) {
        if (checkRun == null) {
            return "";
        }
        String created = checkRun.getCreatedDate() != null ? RUN_LABEL_FORMATTER.format(checkRun.getCreatedDate()) : "n/a";
        String score = checkRun.getScore() != null ? String.format("%.3f", checkRun.getScore()) : "n/a";
        return "%s | %s | %s".formatted(created, nullToDash(checkRun.getAnswerModel()), score);
    }

    private String formatScore(Double score) {
        return score != null ? String.format("%.3f", score) : "-";
    }

    private String formatDelta(Double delta) {
        return delta != null ? String.format("%+.3f", delta) : "-";
    }

    private double roundToThreeDecimals(Double value) {
        if (value == null) {
            return 0d;
        }
        return Math.round(value * 1000d) / 1000d;
    }

    private String getTrend(Double delta) {
        if (delta == null) {
            return messageBundle.getMessage("checkRunComparisonView.trend.na");
        }
        if (delta > 0.0001d) {
            return messageBundle.getMessage("checkRunComparisonView.trend.improved");
        }
        if (delta < -0.0001d) {
            return messageBundle.getMessage("checkRunComparisonView.trend.regressed");
        }
        return messageBundle.getMessage("checkRunComparisonView.trend.unchanged");
    }

    private String nullToDash(String value) {
        return value != null && !value.isBlank() ? value : "-";
    }

    private String formatSubset(Boolean goldenOnly) {
        return Boolean.TRUE.equals(goldenOnly)
                ? messageBundle.getMessage("checkRunComparisonView.subset.golden")
                : messageBundle.getMessage("checkRunComparisonView.subset.allActive");
    }

    @Subscribe("regressionsOnlyField")
    public void onRegressionsOnlyFieldValueChange(com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<Checkbox, Boolean> event) {
        renderGrid(currentRows);
    }

    @Subscribe("historyComparableOnlyField")
    public void onHistoryComparableOnlyFieldValueChange(com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<Checkbox, Boolean> event) {
        renderHistorySection();
    }

    @Subscribe("historyModelFilterField")
    public void onHistoryModelFilterFieldValueChange(com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<JmixComboBox<String>, String> event) {
        renderHistorySection();
    }

    @Subscribe("categoryFilterField")
    public void onCategoryFilterFieldValueChange(com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<JmixComboBox<String>, String> event) {
        renderGrid(currentRows);
    }

    private void renderGrid(List<CheckRunComparisonService.ComparisonRow> rows) {
        if (comparisonGrid == null) {
            comparisonGrid = buildGrid();
            resultsBox.add(comparisonGrid);
        }

        Stream<CheckRunComparisonService.ComparisonRow> stream = rows.stream();
        if (Boolean.TRUE.equals(regressionsOnlyField.getValue())) {
            stream = stream.filter(row -> row.delta() != null && row.delta() < -0.0001d);
        }
        if (categoryFilterField.getValue() != null && !categoryFilterField.getValue().isBlank()) {
            stream = stream.filter(row -> Objects.equals(row.category(), categoryFilterField.getValue()));
        }
        comparisonGrid.setItems(stream.toList());
    }

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize chart config", e);
        }
    }
}
