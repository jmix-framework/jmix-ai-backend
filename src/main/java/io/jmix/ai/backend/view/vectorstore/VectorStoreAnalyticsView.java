package io.jmix.ai.backend.view.vectorstore;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.vectorstore.VectorStoreAnalyticsService;
import io.jmix.ai.backend.vectorstore.VectorStoreAnalyticsService.SnippetSizeBreakdown;
import io.jmix.ai.backend.vectorstore.VectorStoreAnalyticsService.TokenStatistics;
import io.jmix.ai.backend.vectorstore.VectorStoreAnalyticsService.TopicSizeSeries;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.component.model.series.BarSeries;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Route(value = "vector-store-analytics", layout = MainView.class)
@ViewController(id = "VectorStore.analytics")
@ViewDescriptor(path = "vector-store-analytics-view.xml")
public class VectorStoreAnalyticsView extends StandardView {

    @Autowired
    private VectorStoreAnalyticsService analyticsService;
    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private Chart coverageChart;
    @ViewComponent
    private VerticalLayout topicHeatmapBox;
    @ViewComponent
    private Chart sizeChart;
    @ViewComponent
    private Span statsSummary;
    @ViewComponent
    private HorizontalLayout statsCards;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        buildCoverage();
        buildTopicHeatmap();
        buildSizeBreakdown();
    }

    private void buildCoverage() {
        List<VectorStoreAnalyticsService.CorpusCoverage> coverages = analyticsService.loadCorpusCoverage();
        // the shared series (chunks without a Jmix version) appears only when such corpora exist
        boolean hasShared = coverages.stream().anyMatch(coverage -> coverage.shared() > 0);

        List<MapDataItem> items = coverages.stream()
                .map(coverage -> new MapDataItem(Map.of(
                        "corpus", coverage.corpus(),
                        "v2", coverage.v2(),
                        "v3", coverage.v3(),
                        "shared", coverage.shared())))
                .toList();
        coverageChart.withSeries(hasShared
                ? new BarSeries[]{
                        new BarSeries().withName(messageBundle.getMessage("coverage.v2")),
                        new BarSeries().withName(messageBundle.getMessage("coverage.v3")),
                        new BarSeries().withName(messageBundle.getMessage("coverage.shared"))}
                : new BarSeries[]{
                        new BarSeries().withName(messageBundle.getMessage("coverage.v2")),
                        new BarSeries().withName(messageBundle.getMessage("coverage.v3"))});
        coverageChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("corpus")
                .withValueFields(hasShared ? new String[]{"v2", "v3", "shared"} : new String[]{"v2", "v3"})));
    }

    private void buildTopicHeatmap() {
        VectorStoreAnalyticsService.TopicCoverageSummary coverage =
                analyticsService.loadTopicCoverage();

        Div grid = new Div();
        grid.getStyle().set("display", "grid")
                .set("grid-template-columns", "minmax(11em, 20em) 5em 5em")
                .set("gap", "3px").set("max-width", "34em").set("align-items", "stretch");
        grid.add(
                headerCell(messageBundle.getMessage("topic.label"), "left"),
                headerCell(messageBundle.getMessage("coverage.v2"), "center"),
                headerCell(messageBundle.getMessage("coverage.v3"), "center"));
        for (VectorStoreAnalyticsService.TopicCoverage topic : coverage.topics()) {
            grid.add(
                    topicLabelCell(topic.topic()),
                    heatCell(topic.v2(), coverage.maxCount()),
                    heatCell(topic.v3(), coverage.maxCount()));
        }
        grid.add(
                totalLabelCell(messageBundle.getMessage("topicCoverage.total")),
                totalCell(coverage.totalV2()),
                totalCell(coverage.totalV3()));

        topicHeatmapBox.removeAll();
        topicHeatmapBox.setPadding(false);
        topicHeatmapBox.add(grid);
    }

    private Div totalLabelCell(String text) {
        Div c = topicLabelCell(text);
        c.getStyle().set("font-weight", "700").set("border-top", "2px solid var(--lumo-contrast-20pct)");
        return c;
    }

    private Div totalCell(int count) {
        Div c = new Div();
        c.setText(String.valueOf(count));
        c.getStyle().set("font-weight", "700").set("text-align", "center").set("padding", "0.3em")
                .set("border-top", "2px solid var(--lumo-contrast-20pct)")
                .set("font-variant-numeric", "tabular-nums");
        return c;
    }

    private Div headerCell(String text, String align) {
        Div c = new Div();
        c.setText(text);
        c.getStyle().set("font-weight", "700").set("font-size", "0.85em")
                .set("color", "var(--lumo-secondary-text-color)").set("text-align", align)
                .set("padding", "0.2em 0.4em");
        return c;
    }

    private Div topicLabelCell(String topic) {
        Div c = new Div();
        c.setText(topic);
        c.getStyle().set("padding", "0.3em 0.4em").set("white-space", "nowrap")
                .set("overflow", "hidden").set("text-overflow", "ellipsis").set("font-size", "0.9em");
        c.setTitle(topic);
        return c;
    }

    private Div heatCell(int count, int max) {
        Div c = new Div();
        c.setText(String.valueOf(count));
        double intensity = max <= 0 ? 0.0 : (double) count / max;
        double alpha = count == 0 ? 0.0 : 0.12 + 0.85 * intensity;
        c.getStyle()
                .set("background", "rgba(46, 125, 50, " + String.format(Locale.US, "%.2f", alpha) + ")")
                .set("color", intensity > 0.55 ? "white" : "var(--lumo-body-text-color)")
                .set("text-align", "center").set("padding", "0.3em").set("border-radius", "4px")
                .set("font-variant-numeric", "tabular-nums");
        return c;
    }

    private void buildSizeBreakdown() {
        SnippetSizeBreakdown breakdown = analyticsService.loadSnippetSizeBreakdown();
        statsSummary.getStyle().set("color", "var(--lumo-secondary-text-color)");
        statsCards.removeAll();
        statsCards.getStyle().set("flex-wrap", "wrap");
        if (breakdown.statistics() == null) {
            statsSummary.setText(messageBundle.getMessage("stats.empty"));
            return;
        }

        List<MapDataItem> items = new ArrayList<>();
        for (int bucket = 0; bucket < breakdown.bucketStarts().size(); bucket++) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("bucket", String.valueOf(breakdown.bucketStarts().get(bucket)));
            for (int index = 0; index < breakdown.series().size(); index++) {
                values.put("s" + index, breakdown.series().get(index).bucketCounts().get(bucket));
            }
            items.add(new MapDataItem(values));
        }
        String[] valueFields = new String[breakdown.series().size()];
        BarSeries[] barSeries = new BarSeries[breakdown.series().size()];
        for (int index = 0; index < breakdown.series().size(); index++) {
            valueFields[index] = "s" + index;
            barSeries[index] = new BarSeries()
                    .withName(seriesLabel(breakdown.series().get(index)))
                    .withStack("size");
        }
        sizeChart.withSeries(barSeries);
        sizeChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("bucket").withValueFields(valueFields)));

        TokenStatistics statistics = breakdown.statistics();
        statsSummary.setText(messageBundle.formatMessage("stats.summary",
                statistics.count(), Math.round(statistics.average()), Math.round(statistics.median()),
                statistics.min(), statistics.max(), Math.round(statistics.standardDeviation())));
        statsCards.add(
                statCard(messageBundle.getMessage("stats.count"), String.valueOf(statistics.count()),
                        messageBundle.getMessage("stats.count.hint")),
                statCard(messageBundle.getMessage("stats.average"), "%.0f".formatted(statistics.average()),
                        messageBundle.getMessage("stats.average.hint")),
                statCard(messageBundle.getMessage("stats.median"), "%.0f".formatted(statistics.median()),
                        messageBundle.getMessage("stats.median.hint")),
                statCard(messageBundle.getMessage("stats.min"), String.valueOf(statistics.min()),
                        messageBundle.getMessage("stats.min.hint")),
                statCard(messageBundle.getMessage("stats.max"), String.valueOf(statistics.max()),
                        messageBundle.getMessage("stats.max.hint")),
                statCard(messageBundle.getMessage("stats.standardDeviation"),
                        "%.0f".formatted(statistics.standardDeviation()),
                        messageBundle.getMessage("stats.standardDeviation.hint")));
    }

    private String seriesLabel(TopicSizeSeries series) {
        String topic = series.topic() != null
                ? series.topic()
                : messageBundle.getMessage("sizeSeries.otherTopics");
        return messageBundle.formatMessage("sizeSeries.name", topic, Math.round(series.averageTokens()));
    }

    private VerticalLayout statCard(String title, String value, String hint) {
        Span titleSpan = new Span(title);
        titleSpan.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        Span valueSpan = new Span(value);
        valueSpan.getStyle()
                .set("font-size", "var(--lumo-font-size-xl)")
                .set("font-weight", "700")
                .set("font-variant-numeric", "tabular-nums");
        Span hintSpan = new Span(hint);
        hintSpan.getStyle()
                .set("color", "var(--lumo-tertiary-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)");

        VerticalLayout card = new VerticalLayout(titleSpan, valueSpan, hintSpan);
        card.setPadding(true);
        card.setSpacing(false);
        card.setWidth("13em");
        card.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-l)");
        return card;
    }
}
