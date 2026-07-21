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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Route(value = "check-runs/analytics", layout = MainView.class)
@ViewController(id = "CheckRun.analytics")
@ViewDescriptor(path = "check-run-analytics-view.xml")
public class CheckRunAnalyticsView extends StandardView {

    // Category identity of a run point on the time axis: precise enough (year + seconds) that two
    // distinct runs never share a slot, while runs of different configs at the same instant still
    // align on one tick. Undated points get a unique synthetic key instead of collapsing together.
    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss");
    private static final String UNDATED_KEY_PREFIX = "—#";

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

        ChartModel model = buildModel(overview.trends());
        List<String> seriesLabels = trendLabels(overview.trends());

        buildTrendChart(scoreTrendChart, model, seriesLabels,
                messageBundle.getMessage("score.axis"), RunPoint::score);
        buildTrendChart(accuracyTrendChart, model, seriesLabels,
                messageBundle.getMessage("accuracy.axis"), RunPoint::accuracy);
    }

    /** One line per configuration; the axis starts just below the data so small deltas stay visible. */
    private void buildTrendChart(
            Chart chart,
            ChartModel model,
            List<String> seriesLabels,
            String axisName,
            Function<RunPoint, Number> metric) {
        List<String> categories = model.categories();
        int trendCount = model.pointsByTrend().size();
        List<Map<String, Number>> valuesByTrend = new ArrayList<>(trendCount);
        double min = 1.0;
        boolean hasData = false;
        for (Map<String, RunPoint> points : model.pointsByTrend()) {
            Map<String, Number> values = new HashMap<>();
            for (Map.Entry<String, RunPoint> point : points.entrySet()) {
                Number value = metric.apply(point.getValue());
                if (value != null) {
                    values.put(point.getKey(), value);
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
            for (int index = 0; index < trendCount; index++) {
                Number value = valuesByTrend.get(index).get(category);
                // "-" is the ECharts empty-value marker; nulls would be dropped from the dataset row,
                // breaking dimension inference and shifting series values across categories
                row.put("s" + index, value != null ? value : "-");
            }
            items.add(new MapDataItem(row));
        }

        String[] valueFields = new String[trendCount];
        LineSeries[] series = new LineSeries[trendCount];
        for (int index = 0; index < trendCount; index++) {
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

    /**
     * Aligns every run point onto the shared time axis. Each point keeps its own slot — keyed by a
     * precise timestamp, or a unique synthetic key when undated — so two runs in the same minute,
     * across years, or without a date are no longer collapsed onto one another. Runs of different
     * configs at the same instant still share a slot, which keeps them aligned on the axis.
     */
    static ChartModel buildModel(List<ConfigTrend> trends) {
        List<Map<String, RunPoint>> pointsByTrend = new ArrayList<>(trends.size());
        List<Entry> union = new ArrayList<>();
        for (int index = 0; index < trends.size(); index++) {
            pointsByTrend.add(new LinkedHashMap<>());
            for (RunPoint point : trends.get(index).points()) {
                union.add(new Entry(index, point));
            }
        }
        union.sort(Comparator.comparing((Entry entry) -> entry.point().createdDate(),
                Comparator.nullsFirst(Comparator.naturalOrder())));

        List<String> categories = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int undated = 0;
        for (Entry entry : union) {
            String key = entry.point().createdDate() != null
                    ? entry.point().createdDate().format(KEY_FORMAT)
                    : UNDATED_KEY_PREFIX + undated++;
            if (seen.add(key)) {
                categories.add(key);
            }
            pointsByTrend.get(entry.trendIndex()).put(key, entry.point());
        }
        return new ChartModel(categories, pointsByTrend);
    }

    private record Entry(int trendIndex, RunPoint point) {
    }

    /** Chart-ready trend data: the ordered x-axis categories and, per trend, its point at each. */
    record ChartModel(List<String> categories, List<Map<String, RunPoint>> pointsByTrend) {
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
