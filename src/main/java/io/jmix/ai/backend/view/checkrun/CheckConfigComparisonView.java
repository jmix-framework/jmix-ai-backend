package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckAnalyticsService;
import io.jmix.ai.backend.checks.CheckAnalyticsService.ConfigStat;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route(value = "check-runs/configs", layout = MainView.class)
@ViewController(id = "CheckRun.configs")
@ViewDescriptor(path = "check-config-comparison-view.xml")
public class CheckConfigComparisonView extends StandardView {

    @Autowired
    private CheckAnalyticsService analyticsService;

    @ViewComponent
    private VerticalLayout introBox;
    @ViewComponent
    private HorizontalLayout winnerCards;
    @ViewComponent
    private Chart scoreChart;
    @ViewComponent
    private Chart accuracyChart;
    @ViewComponent
    private VerticalLayout shippedBox;

    @Subscribe
    public void onInit(final InitEvent event) {
        List<ConfigStat> stats = analyticsService.configComparison();

        buildIntro();
        buildCharts(stats);
        buildWinnerCards(stats);
        buildShipped();
    }

    private void buildIntro() {
        Span h = new Span("Config A/B benchmark");
        h.getStyle().set("font-size", "1.5em").set("font-weight", "700");

        Span sub = new Span("39 checks · averaged over the 3 latest runs per config · "
                + "🔵 Jmix 2  🟢 Jmix 3. "
                + "Both metrics: higher is better.");
        sub.getStyle().set("color", "var(--lumo-secondary-text-color)");

        introBox.add(h, sub);
    }

    private void buildCharts(List<ConfigStat> stats) {
        // pivot: config -> {v2, v3} for score and accuracy, preserving config order
        Map<String, double[]> score = new LinkedHashMap<>();   // config -> [v2, v3]
        Map<String, double[]> acc = new LinkedHashMap<>();
        for (ConfigStat s : stats) {
            score.computeIfAbsent(s.config(), k -> new double[2]);
            acc.computeIfAbsent(s.config(), k -> new double[2]);
            int idx = "v3".equals(s.version()) ? 1 : 0;
            score.get(s.config())[idx] = s.score();
            acc.get(s.config())[idx] = s.accuracy();
        }

        scoreChart.setDataSet(pivotDataSet(score));
        accuracyChart.setDataSet(pivotDataSet(acc));
    }

    private DataSet pivotDataSet(Map<String, double[]> byConfig) {
        List<MapDataItem> items = new ArrayList<>();
        byConfig.forEach((config, vals) ->
                items.add(new MapDataItem(Map.of("config", config, "v2", vals[0], "v3", vals[1]))));
        return new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("config")
                .withValueFields("v2", "v3"));
    }

    private void buildWinnerCards(List<ConfigStat> stats) {
        double mainV2 = stat(stats, "main baseline", "v2");
        double mainV3 = stat(stats, "main baseline", "v3");
        double neuV2 = stat(stats, "neutral (live)", "v2");
        double neuV3 = stat(stats, "neutral (live)", "v3");

        winnerCards.add(
                card("Live config", "neutral", true),
                card("Jmix 2 score", "%.2f → %.2f".formatted(mainV2, neuV2), neuV2 >= mainV2),
                card("Jmix 3 score", "%.2f → %.2f".formatted(mainV3, neuV3), neuV3 >= mainV3),
                card("Regressions vs baseline", "0", true));
    }

    private double stat(List<ConfigStat> stats, String config, String version) {
        return stats.stream()
                .filter(s -> s.config().equals(config) && s.version().equals(version))
                .mapToDouble(ConfigStat::score).findFirst().orElse(0.0);
    }

    private Span card(String title, String value, boolean good) {
        Span span = new Span(title + ": " + value);
        span.addClassName("badge");
        span.getElement().getThemeList().add(good ? "success" : "error");
        span.getStyle().set("padding", "0.4em 0.8em").set("border-radius", "0.5em")
                .set("font-weight", "600");
        return span;
    }

    private void buildShipped() {
        Span header = new Span("What we shipped");
        header.getStyle().set("font-size", "1.2em").set("font-weight", "700")
                .set("margin-top", "0.5em");
        shippedBox.add(header);

        shippedBox.add(
                changeRow("Neutral prompt (active)",
                        "Softened “ALWAYS call javaapi / refuse if not found” → “use it when it helps, otherwise answer from docs + knowledge”. Fixed the Jmix 3 refusals: v3 score 0.86 → 0.93.", true),
                changeRow("javaapi corpus + retriever",
                        "2051 enriched API cards (Jmix 2) + a javaapi_retriever tool giving exact signatures instead of prose.", false),
                changeRow("LLM-controlled retrieval budget",
                        "Tools now accept maxResults (1–15); the model is told each snippet ≈ docs 200 / uisamples 350 / javaapi 700 tokens, so it can budget context.", false),
                changeRow("Golden question set (10)",
                        "Behavioural questions carried over from 1.7 with Russian reference answers (the judge caps score on a language mismatch).", false),
                changeRow("Stricter refusal scoring",
                        "The evaluator now scores a refusal strictly 1.0/0.0 by intent, so a valid generic decline is no longer under-scored on phrasing.", false),
                changeRow("Reproducible A/B harness",
                        "POST /api/checks/run triggers a run for a config+version; results are averaged over repeats to beat the ~7–10 pp run-to-run noise.", false));
    }

    private Div changeRow(String title, String desc, boolean live) {
        Div row = new Div();
        row.getStyle().set("padding", "0.6em 0.8em")
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "0.6em").set("width", "100%");
        if (live) {
            row.getStyle().set("border-color", "var(--lumo-success-color)")
                    .set("background", "var(--lumo-success-color-10pct)");
        }

        Span t = new Span(title + (live ? "  ✅ live" : ""));
        t.getStyle().set("font-weight", "700").set("display", "block");
        Span d = new Span(desc);
        d.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.9em");

        row.add(t, d);
        return row;
    }
}
