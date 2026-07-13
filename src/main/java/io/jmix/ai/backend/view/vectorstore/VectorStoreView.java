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
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheCleanupService;
import io.jmix.ai.backend.vectorstore.Ingester;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.ai.backend.vectorstore.VectorStoreAnalyticsService;
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
import org.springframework.lang.Nullable;

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
    private VectorStoreAnalyticsService vectorStoreAnalyticsService;
    @Autowired
    private EnrichmentCacheCleanupService enrichmentCacheCleanupService;
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
    @ViewComponent
    private MessageBundle messageBundle;

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
        List<MapDataItem> items = vectorStoreAnalyticsService.loadTopTokenAverages().stream()
                .map(average -> new MapDataItem(Map.of(
                        "topic", average.topic(), "tokens", average.tokens())))
                .toList();
        tokenByTopicChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("topic").withValueFields("tokens")));
    }

    private void buildTokenDistribution() {
        VectorStoreAnalyticsService.TokenDistribution distribution =
                vectorStoreAnalyticsService.loadTokenDistribution();
        tokenStatsCards.removeAll();
        if (distribution.statistics() == null) {
            tokenHistogramChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                    .withDataProvider(new ListChartItems<>(List.of()))
                    .withCategoryField("bucket").withValueFields("snippets")));
            return;
        }
        VectorStoreAnalyticsService.TokenStatistics statistics = distribution.statistics();

        tokenStatsCards.add(
                statCard(messageBundle.getMessage("stats.count"), String.valueOf(statistics.count())),
                statCard(messageBundle.getMessage("stats.min"), String.valueOf(statistics.min())),
                statCard(messageBundle.getMessage("stats.median"), "%.0f".formatted(statistics.median())),
                statCard(messageBundle.getMessage("stats.average"), "%.0f".formatted(statistics.average())),
                statCard(messageBundle.getMessage("stats.max"), String.valueOf(statistics.max())),
                statCard(messageBundle.getMessage("stats.standardDeviation"),
                        "%.0f".formatted(statistics.standardDeviation())));

        List<MapDataItem> bars = distribution.buckets().stream()
                .map(bucket -> new MapDataItem(Map.of(
                        "bucket", String.valueOf(bucket.start()), "snippets", bucket.snippets())))
                .toList();
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
        VectorStoreAnalyticsService.TopicCoverageSummary coverage =
                vectorStoreAnalyticsService.loadTopTopicCoverage();

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

        topicHeatmapBox.removeAll();
        topicHeatmapBox.setPadding(false);
        topicHeatmapBox.add(grid);
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
        List<MapDataItem> items = vectorStoreAnalyticsService.loadCorpusCoverage().stream()
                .map(coverage -> new MapDataItem(Map.of(
                        "corpus", coverage.corpus(),
                        "v2", coverage.v2(),
                        "v3", coverage.v3(),
                        "shared", coverage.shared())))
                .toList();
        coverageChart.setDataSet(new DataSet().withSource(new DataSet.Source<MapDataItem>()
                .withDataProvider(new ListChartItems<>(items))
                .withCategoryField("corpus").withValueFields("v2", "v3", "shared")));
    }

    private void buildUpdateMenuItems() {
        for (Ingester ingester : ingesterManager.getIngesters()) {
            List<JmixVersion> versions = ingester.getVersions();
            if (versions.isEmpty()) {
                addUpdateMenuItem(ingester.getType(), null);
            } else {
                for (JmixVersion version : versions) {
                    addUpdateMenuItem(ingester.getType(), version);
                }
            }
        }
        updateButton.addItem("all", "Update all data").addClickListener(clickEvent -> confirmAll());
    }

    private void addUpdateMenuItem(String type, @Nullable JmixVersion version) {
        String itemId = version == null ? type : type + "-" + version.getId();
        String label = version == null
                ? "Update " + type
                : "Update " + type + " (" + version.getId() + ")";
        updateButton.addItem(itemId, label).addClickListener(clickEvent ->
                dialogs.createOptionDialog()
                        .withHeader("Confirm")
                        .withText(version == null
                                ? "Update all data of type '%s'?".formatted(type)
                                : "Update %s (%s)?".formatted(type, version.getId()))
                        .withActions(
                                new DialogAction(DialogAction.Type.YES).withHandler(e ->
                                        updateInBackground(new UpdateByTypeAndVersionTask(type, version))),
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

    @Subscribe(id = "cleanupCacheButton", subject = "clickListener")
    public void onCleanupCacheButtonClick(final ClickEvent<JmixButton> event) {
        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("cacheCleanup.confirmHeader"))
                .withText(messageBundle.getMessage("cacheCleanup.confirmText"))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e ->
                                cleanupCacheInBackground()),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    private void cleanupCacheInBackground() {
        dialogs.createBackgroundTaskDialog(new CleanupCacheTask())
                .withHeader(messageBundle.getMessage("cacheCleanup.runningHeader"))
                .withText(messageBundle.getMessage("cacheCleanup.runningText"))
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

    private class UpdateByTypeAndVersionTask extends UpdateTask {

        private final String type;
        @Nullable
        private final JmixVersion version;

        private UpdateByTypeAndVersionTask(String type, @Nullable JmixVersion version) {
            this.type = type;
            this.version = version;
        }

        @Override
        public String run(TaskLifeCycle<Integer> taskLifeCycle) throws Exception {
            return version == null
                    ? ingesterManager.updateByType(type)
                    : ingesterManager.updateByTypeAndVersion(type, version);
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

    private class CleanupCacheTask extends BackgroundTask<Integer, EnrichmentCacheCleanupService.CleanupResult> {

        private CleanupCacheTask() {
            super(60, TimeUnit.MINUTES);
        }

        @Override
        public EnrichmentCacheCleanupService.CleanupResult run(TaskLifeCycle<Integer> taskLifeCycle) {
            return enrichmentCacheCleanupService.cleanup();
        }

        @Override
        public void done(EnrichmentCacheCleanupService.CleanupResult result) {
            notifications.show(messageBundle.formatMessage(
                    "cacheCleanup.success",
                    result.deletedEntries(),
                    result.deletedGenerations(),
                    result.skippedScopes()));
        }

        @Override
        public boolean handleException(Exception ex) {
            defaultUiExceptionHandler.handle(ex);
            return true;
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
