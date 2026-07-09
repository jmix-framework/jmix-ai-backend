package io.jmix.ai.backend.view.vectorstore;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.Ingester;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.core.DataLoadContext;
import io.jmix.core.LoadContext;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.SupportsTypedValue;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.exception.DefaultUiExceptionHandler;
import io.jmix.flowui.facet.UrlQueryParametersFacet;
import io.jmix.flowui.facet.urlqueryparameters.AbstractUrlQueryParametersBinder;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.combobutton.ComboButton;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.util.RemoveOperation;
import io.jmix.flowui.view.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


@Route(value = "vector-store", layout = MainView.class)
@ViewController(id = "VectorStoreView")
@ViewDescriptor(path = "vector-store-view.xml")
@LookupComponent("vectorStoreDataGrid")
@DialogMode(width = "64em")
public class VectorStoreView extends StandardListView<VectorStoreEntity> {

    @Autowired
    private IngesterManager ingesterManager;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private VectorStoreRepository vectorStoreRepository;
    @Autowired
    private Notifications notifications;
    @Autowired
    private DefaultUiExceptionHandler defaultUiExceptionHandler;

    @ViewComponent
    private CollectionLoader<VectorStoreEntity> vectorStoreDl;
    @ViewComponent
    private TypedTextField<String> filterField;
    @ViewComponent
    private ComboButton updateButton;
    @ViewComponent
    private DataGrid<VectorStoreEntity> vectorStoreDataGrid;
    @ViewComponent
    private UrlQueryParametersFacet urlQueryParameters;
    @ViewComponent
    private Chart coverageChart;
    @ViewComponent
    private VerticalLayout topicHeatmapBox;
    @ViewComponent
    private Chart tokenByTopicChart;
    @ViewComponent
    private Chart tokenHistogramChart;
    @ViewComponent
    private HorizontalLayout tokenStatsCards;

    @Subscribe
    public void onInit(final InitEvent event) {
        buildUpdateMenuItems();
        refreshAnalytics();
        urlQueryParameters.registerBinder(new FilterUrlQueryParametersBinder());
    }

    /** Rebuilds the corpus charts and topic heatmap; call after the corpus changes (ingest/delete). */
    private void refreshAnalytics() {
        buildCoverage();
        buildTopicHeatmap();
        buildTokenByTopic();
        buildTokenDistribution();
    }

    private void buildTokenByTopic() {
        List<MapDataItem> items = vectorStoreRepository.avgSnippetTokensByTopic().stream()
                .filter(r -> r[0] != null && !((String) r[0]).isBlank())
                .sorted((a, b) -> Integer.compare((int) b[1], (int) a[1]))
                .limit(20)
                .map(r -> new MapDataItem(Map.of("topic", r[0], "tokens", r[1])))
                .toList();
        tokenByTopicChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("topic").withValueFields("tokens")));
    }

    private void buildTokenDistribution() {
        List<Integer> sizes = vectorStoreRepository.snippetTokenSizes();
        tokenStatsCards.removeAll();
        if (sizes.isEmpty()) {
            tokenHistogramChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                    .withDataProvider(new ListChartItems<>(List.of()))
                    .withCategoryField("bucket").withValueFields("snippets")));
            return;
        }
        List<Integer> sorted = sizes.stream().sorted().toList();
        int n = sorted.size();
        int min = sorted.get(0);
        int max = sorted.get(n - 1);
        double avg = sorted.stream().mapToInt(Integer::intValue).average().orElse(0);
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        double stddev = Math.sqrt(sorted.stream().mapToDouble(s -> (s - avg) * (s - avg)).sum() / n);

        tokenStatsCards.add(
                statCard("count", String.valueOf(n)),
                statCard("min", String.valueOf(min)),
                statCard("median", "%.0f".formatted(median)),
                statCard("avg", "%.0f".formatted(avg)),
                statCard("max", String.valueOf(max)),
                statCard("std dev", "%.0f".formatted(stddev)));

        // histogram: ~20 fixed-width buckets from min..max
        int buckets = 20;
        int range = Math.max(1, max - min);
        int width = (int) Math.ceil(range / (double) buckets);
        int[] counts = new int[buckets];
        for (int s : sorted) {
            int idx = Math.min(buckets - 1, (s - min) / width);
            counts[idx]++;
        }
        List<MapDataItem> bars = new java.util.ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            int start = min + i * width;
            bars.add(new MapDataItem(Map.of("bucket", String.valueOf(start), "snippets", counts[i])));
        }
        tokenHistogramChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(bars))
                .withCategoryField("bucket").withValueFields("snippets")));
    }

    private Span statCard(String title, String value) {
        Span span = new Span(title + ": " + value);
        span.getStyle().set("padding", "0.35em 0.7em").set("border-radius", "0.5em")
                .set("background", "var(--lumo-contrast-5pct)").set("font-weight", "600")
                .set("font-variant-numeric", "tabular-nums");
        return span;
    }

    private void buildTopicHeatmap() {
        Map<String, int[]> byTopic = new java.util.LinkedHashMap<>();
        for (Object[] row : vectorStoreRepository.countSnippetTopicByVersion()) {
            String topic = (String) row[0];
            String version = (String) row[1];
            int count = (int) row[2];
            if (topic == null || topic.isBlank()) {
                continue;
            }
            int[] vv = byTopic.computeIfAbsent(topic, k -> new int[2]);
            if ("v3".equalsIgnoreCase(version)) {
                vv[1] += count;
            } else {
                vv[0] += count;
            }
        }

        List<Map.Entry<String, int[]>> top = byTopic.entrySet().stream()
                .sorted((a, b) -> Integer.compare(total(b.getValue()), total(a.getValue())))
                .limit(24)
                .toList();
        int max = top.stream()
                .flatMapToInt(e -> java.util.stream.IntStream.of(e.getValue()[0], e.getValue()[1]))
                .max().orElse(1);

        Div grid = new Div();
        grid.getStyle().set("display", "grid")
                .set("grid-template-columns", "minmax(11em, 20em) 5em 5em")
                .set("gap", "3px").set("max-width", "34em").set("align-items", "stretch");
        grid.add(headerCell("Topic", "left"), headerCell("Jmix 2", "center"), headerCell("Jmix 3", "center"));
        for (Map.Entry<String, int[]> e : top) {
            grid.add(topicLabelCell(e.getKey()), heatCell(e.getValue()[0], max), heatCell(e.getValue()[1], max));
        }

        topicHeatmapBox.removeAll();
        topicHeatmapBox.setPadding(false);
        topicHeatmapBox.add(grid);
    }

    private static int total(int[] vv) {
        return vv[0] + vv[1];
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
                .set("background", "rgba(46, 125, 50, " + String.format(java.util.Locale.US, "%.2f", alpha) + ")")
                .set("color", intensity > 0.55 ? "white" : "var(--lumo-body-text-color)")
                .set("text-align", "center").set("padding", "0.3em").set("border-radius", "4px")
                .set("font-variant-numeric", "tabular-nums");
        return c;
    }

    private void buildCoverage() {
        Map<String, int[]> byType = new java.util.LinkedHashMap<>();
        for (Object[] row : vectorStoreRepository.countByTypeAndVersion()) {
            String type = (String) row[0];
            String version = (String) row[1];
            int count = (int) row[2];
            if (type == null) {
                continue;
            }
            // show the searchable snippet corpus, not the raw pre-snippet chunks
            if ("docs".equals(type) || "uisamples".equals(type)) {
                continue;
            }
            int[] vv = byType.computeIfAbsent(type, k -> new int[2]);
            if ("v3".equalsIgnoreCase(version)) {
                vv[1] += count;
            } else {
                vv[0] += count;
            }
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        byType.forEach((type, vv) -> rows.add(Map.of("corpus", type, "v2", vv[0], "v3", vv[1])));
        coverageChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(rows.stream().map(MapDataItem::new).toList()))
                .withCategoryField("corpus").withValueFields("v2", "v3")));
    }

    private void buildUpdateMenuItems() {
        for (Ingester ingester : ingesterManager.getIngesters()) {
            addUpdateMenuItem(ingester.getType());
        }
        updateButton.addItem("all", "Update all data").addClickListener(clickEvent -> confirmAll());
    }

    private void addUpdateMenuItem(String type) {
        updateButton.addItem(type, "Update " + type).addClickListener(clickEvent ->
                dialogs.createOptionDialog()
                        .withHeader("Confirm")
                        .withText("Update all data of type '%s'?".formatted(type))
                        .withActions(
                                new DialogAction(DialogAction.Type.YES).withHandler(e ->
                                        updateInBackground(new UpdateByTypeTask(type))),
                                new DialogAction(DialogAction.Type.NO)
                        )
                        .open());
    }

    private void confirmAll() {
        dialogs.createOptionDialog()
                .withHeader("Confirm")
                .withText("Update all data?")
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e ->
                                updateInBackground(new UpdateTask())),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }


    @Install(to = "vectorStoreDl", target = Target.DATA_LOADER)
    private List<VectorStoreEntity> vectorStoreDlLoadDelegate(final LoadContext<VectorStoreEntity> loadContext) {
        LoadContext.Query loadContextQuery = Objects.requireNonNull(loadContext.getQuery());
        return vectorStoreRepository.loadList(filterField.getTypedValue(), loadContextQuery.getFirstResult(), loadContextQuery.getMaxResults());
    }

    @Install(to = "pagination", subject = "totalCountDelegate")
    private Integer paginationTotalCountDelegate(final DataLoadContext dataLoadContext) {
        return vectorStoreRepository.getCount(filterField.getTypedValue());
    }

    @Subscribe(id = "filterHelpButton", subject = "clickListener")
    public void onFilterHelpButtonClick(final ClickEvent<JmixButton> event) {
        UI.getCurrent().getPage().open("https://docs.spring.io/spring-ai/reference/api/vectordbs.html#_filter_string", "_blank");
    }

    @Subscribe("filterField")
    public void onFilterFieldTypedValueChange(final SupportsTypedValue.TypedValueChangeEvent<TypedTextField<String>, String> event) {
        vectorStoreDl.load();
    }

    @Subscribe(id = "updateButton", subject = "clickListener")
    public void onUpdateButtonClick(final ClickEvent<JmixButton> event) {
        VectorStoreEntity entity = vectorStoreDataGrid.getSingleSelectedItem();
        if (entity == null) {
            notifications.show("Select row to update");
        } else {
            updateInBackground(new UpdateByEntityTask(entity));
        }
    }

    private void updateInBackground(UpdateTask task) {
        dialogs.createBackgroundTaskDialog(task)
                .withHeader("Updating vector store data")
                .withText("Please wait...")
                .open();
    }

    @Subscribe(id = "removeAllButton", subject = "clickListener")
    public void onRemoveAllButtonClick(final ClickEvent<JmixButton> event) {
        dialogs.createOptionDialog()
                .withHeader("Warning")
                .withText("Remove all vector store data" + (StringUtils.isBlank(filterField.getTypedValue()) ? "?" : " for the current filter?"))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e -> {
                            vectorStoreRepository.delete(filterField.getTypedValue());
                            vectorStoreDl.load();
                            refreshAnalytics();
                        }),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    @Install(to = "vectorStoreDataGrid.removeAction", subject = "delegate")
    public void vectorStoreDataGridRemoveActionDelegate(final Collection<VectorStoreEntity> collection) {
        List<VectorStoreEntity> entities = getEntitiesOfTheSameSource(collection.iterator().next());
        vectorStoreRepository.delete(entities);
        refreshAnalytics();
    }

    @Install(to = "vectorStoreDataGrid.removeAction", subject = "beforeActionPerformedHandler")
    private void vectorStoreDataGridRemoveActionBeforeActionPerformedHandler(final RemoveOperation.BeforeActionPerformedEvent<VectorStoreEntity> beforeActionPerformedEvent) {
        VectorStoreEntity selectedEntity = vectorStoreDataGrid.getSingleSelectedItem();
        if (selectedEntity == null)
            return;

        List<VectorStoreEntity> entities = getEntitiesOfTheSameSource(selectedEntity);
        if (entities.size() > 1) {
            beforeActionPerformedEvent.preventAction();
            dialogs.createOptionDialog()
                    .withHeader("Please confirm")
                    .withText("There are multiple chunks of this source. Remove them all?")
                    .withActions(
                            new DialogAction(DialogAction.Type.OK)
                                    .withVariant(ActionVariant.PRIMARY)
                                    .withHandler(e -> {
                                        vectorStoreRepository.delete(entities);
                                        vectorStoreDl.load();
                                        refreshAnalytics();
                                    }),
                            new DialogAction(DialogAction.Type.CANCEL)
                    )
                    .open();
        }
    }

    private List<VectorStoreEntity> getEntitiesOfTheSameSource(VectorStoreEntity selectedEntity) {
        Map<String, Object> metadata = selectedEntity.getMetadataMap();
        String type = (String) metadata.get("type");
        String source = (String) metadata.get("source");
        List<VectorStoreEntity> entities = vectorStoreRepository.loadList("type == '%s' && source == '%s'".formatted(type, source));
        return entities;
    }

    @Subscribe(id = "filterClearButton", subject = "clickListener")
    public void onFilterClearButtonClick(final ClickEvent<JmixButton> event) {
        filterField.setValue("");
    }

    private class UpdateTask extends BackgroundTask<Integer, String> {

        protected UpdateTask() {
            super(60, TimeUnit.MINUTES);
        }

        @Override
        public String run(TaskLifeCycle<Integer> taskLifeCycle) throws Exception {
            return ingesterManager.update();
        }

        @Override
        public void done(String result) {
            dialogs.createMessageDialog()
                    .withContent(new Html("<p>" + result + "</p>"))
                    .withHeader("Update result")
                    .open();
            vectorStoreDl.load();
            refreshAnalytics();
        }

        @Override
        public boolean handleException(Exception ex) {
            defaultUiExceptionHandler.handle(ex);
            return true;
        }
    }

    private class UpdateByTypeTask extends UpdateTask {

        private final String type;

        private UpdateByTypeTask(String type) {
            this.type = type;
        }

        @Override
        public String run(TaskLifeCycle<Integer> taskLifeCycle) throws Exception {
            return ingesterManager.updateByType(type);
        }
    }

    private class UpdateByEntityTask extends UpdateTask {

        private final VectorStoreEntity entity;

        public UpdateByEntityTask(VectorStoreEntity entity) {
            this.entity = entity;
        }

        @Override
        public String run(TaskLifeCycle<Integer> taskLifeCycle) throws Exception {
            return ingesterManager.updateByEntity(entity);
        }
    }

    private class FilterUrlQueryParametersBinder extends AbstractUrlQueryParametersBinder {

        public FilterUrlQueryParametersBinder() {
            filterField.addValueChangeListener(event -> {
                String text = event.getValue();
                QueryParameters qp = QueryParameters.of("filter", text);
                fireQueryParametersChanged(new UrlQueryParametersFacet.UrlQueryParametersChangeEvent(this, qp));
            });
        }

        @Override
        public void updateState(QueryParameters queryParameters) {
            String text = queryParameters.getSingleParameter("filter").orElse("");
            filterField.setValue(text);
        }

        @Override
        public Component getComponent() {
            return null;
        }
    }
}