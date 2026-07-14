package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckAnalyticsService;
import io.jmix.ai.backend.checks.CheckAnalyticsService.ConfigTrend;
import io.jmix.ai.backend.checks.CheckAnalyticsService.Overview;
import io.jmix.ai.backend.checks.CheckAnalyticsService.RunPoint;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.component.model.axis.AxisType;
import io.jmix.chartsflowui.kit.component.model.axis.YAxis;
import io.jmix.chartsflowui.kit.component.model.series.LineSeries;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Route(value = "check-runs/analytics", layout = MainView.class)
@ViewController(id = "CheckRun.analytics")
@ViewDescriptor(path = "check-run-analytics-view.xml")
public class CheckRunAnalyticsView extends StandardView {

    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Autowired
    private CheckAnalyticsService analyticsService;
    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private HorizontalLayout summaryCards;
    @ViewComponent
    private Chart scoreTrendChart;
    @ViewComponent
    private Chart accuracyTrendChart;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        render(analyticsService.loadOverview());
    }

    private void render(Overview overview) {
        summaryCards.removeAll();
        summaryCards.getStyle().set("flex-wrap", "wrap");
        summaryCards.add(
                card(messageBundle.getMessage("overview.completedRuns"), String.valueOf(overview.completedRuns())),
                card(messageBundle.getMessage("overview.configCount"), String.valueOf(overview.configCount())),
                card(messageBundle.getMessage("overview.latestScore"), formatMetric(overview.latestScore())),
                card(messageBundle.getMessage("overview.latestAccuracy"), formatMetric(overview.latestAccuracy())));

        List<String> categories = overview.trends().stream()
                .flatMap(trend -> trend.points().stream())
                .sorted(Comparator.comparing(RunPoint::createdDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(this::pointLabel)
                .distinct()
                .toList();
        List<String> seriesLabels = trendLabels(overview.trends());

        buildTrendChart(scoreTrendChart, overview.trends(), seriesLabels, categories,
                messageBundle.getMessage("score.axis"), point -> point.score());
        buildTrendChart(accuracyTrendChart, overview.trends(), seriesLabels, categories,
                messageBundle.getMessage("accuracy.axis"), RunPoint::accuracy);
    }

    /** One line per configuration; the axis starts just below the data so small deltas stay visible. */
    private void buildTrendChart(Chart chart, List<ConfigTrend> trends, List<String> seriesLabels,
                                 List<String> categories, String axisName,
                                 Function<RunPoint, Number> metric) {
        List<Map<String, Number>> valuesByTrend = new ArrayList<>(trends.size());
        double min = 1.0;
        boolean hasData = false;
        for (ConfigTrend trend : trends) {
            Map<String, Number> values = new HashMap<>();
            for (RunPoint point : trend.points()) {
                Number value = metric.apply(point);
                if (value != null) {
                    values.put(pointLabel(point), value);
                    min = Math.min(min, value.doubleValue());
                    hasData = true;
                }
            }
            valuesByTrend.add(values);
        }

        List<MapDataItem> items = new ArrayList<>(categories.size());
        for (String category : categories) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", category);
            for (int index = 0; index < trends.size(); index++) {
                Number value = valuesByTrend.get(index).get(category);
                // "-" is the ECharts empty-value marker; nulls would be dropped from the dataset row,
                // breaking dimension inference and shifting series values across categories
                row.put("s" + index, value != null ? value : "-");
            }
            items.add(new MapDataItem(row));
        }

        String[] valueFields = new String[trends.size()];
        LineSeries[] series = new LineSeries[trends.size()];
        for (int index = 0; index < trends.size(); index++) {
            valueFields[index] = "s" + index;
            series[index] = new LineSeries()
                    .withName(seriesLabels.get(index))
                    .withConnectNulls(true);
        }
        chart.withSeries(series);
        chart.withYAxis(new YAxis()
                .withType(AxisType.VALUE)
                .withName(axisName)
                .withMin(axisMin(min, hasData))
                .withMax("1"));
        chart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("label")
                .withValueFields(valueFields)));
    }

    /** Config labels for the legend; a short fingerprint disambiguates identical labels. */
    private List<String> trendLabels(List<ConfigTrend> trends) {
        Map<String, Integer> labelCounts = new HashMap<>();
        for (ConfigTrend trend : trends) {
            labelCounts.merge(baseLabel(trend), 1, Integer::sum);
        }
        return trends.stream()
                .map(trend -> labelCounts.get(baseLabel(trend)) > 1
                        ? messageBundle.formatMessage("trendSeries.nameWithFingerprint",
                                trend.version(), displayLabel(trend), trend.fingerprint())
                        : baseLabel(trend))
                .toList();
    }

    private String baseLabel(ConfigTrend trend) {
        return messageBundle.formatMessage("trendSeries.name", trend.version(), displayLabel(trend));
    }

    private String displayLabel(ConfigTrend trend) {
        return !trend.label().isBlank()
                ? shorten(trend.label())
                : messageBundle.getMessage("config.unlabeled");
    }

    private String pointLabel(RunPoint point) {
        return point.createdDate() != null ? point.createdDate().format(LABEL_FORMAT) : "—";
    }

    private static String axisMin(double min, boolean hasData) {
        if (!hasData) {
            return "0";
        }
        double floored = Math.floor((min - 0.02) * 10) / 10;
        return String.valueOf(Math.max(0.0, Math.min(0.9, floored)));
    }

    private VerticalLayout card(String title, String value) {
        Span titleSpan = new Span(title);
        titleSpan.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        Span valueSpan = new Span(value);
        valueSpan.getStyle()
                .set("font-size", "var(--lumo-font-size-xl)")
                .set("font-weight", "700")
                .set("font-variant-numeric", "tabular-nums");

        VerticalLayout card = new VerticalLayout(titleSpan, valueSpan);
        card.setPadding(true);
        card.setSpacing(false);
        card.setWidth("12em");
        card.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-l)");
        return card;
    }

    private static String formatMetric(Double value) {
        return value != null ? "%.2f".formatted(value) : "—";
    }

    private static String shorten(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }
}
