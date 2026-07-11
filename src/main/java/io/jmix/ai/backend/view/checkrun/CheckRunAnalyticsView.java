package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckAnalyticsService;
import io.jmix.ai.backend.checks.CheckAnalyticsService.Overview;
import io.jmix.ai.backend.checks.CheckAnalyticsService.RunInfo;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private Chart trendChart;

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

        List<MapDataItem> items = overview.runs().stream()
                .map(this::chartItem)
                .toList();
        trendChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("label")
                .withValueFields("score", "accuracy")));
    }

    private MapDataItem chartItem(RunInfo run) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", runLabel(run));
        values.put("score", run.score());
        if (run.accuracy() != null) {
            values.put("accuracy", run.accuracy());
        }
        return new MapDataItem(values);
    }

    private String runLabel(RunInfo run) {
        String date = run.createdDate() != null ? run.createdDate().format(LABEL_FORMAT) : "—";
        String version = !run.version().isBlank() ? run.version() : "—";
        String config = !run.config().isBlank()
                ? shorten(run.config())
                : messageBundle.getMessage("config.unlabeled");
        return messageBundle.formatMessage("overview.runLabel", date, version, config);
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
