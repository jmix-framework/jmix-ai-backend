package io.jmix.ai.backend.view.vectorstore;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.view.main.MainView;
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

    @Subscribe
    public void onInit(final InitEvent event) {
        for (String type : ingesterManager.getTypes()) {
            updateButton.addItem(type, "Update " + type).addClickListener(clickEvent -> {
                dialogs.createOptionDialog()
                        .withHeader("Confirm")
                        .withText("Update all data of type '%s'?".formatted(type))
                        .withActions(
                                new DialogAction(DialogAction.Type.YES).withHandler(e ->
                                        updateInBackground(new UpdateByTypeTask(type))),
                                new DialogAction(DialogAction.Type.NO)
                        )
                        .open();
            });
        }
        updateButton.addItem("all", "Update all data").addClickListener(clickEvent -> {
            dialogs.createOptionDialog()
                    .withHeader("Confirm")
                    .withText("Update all data?")
                    .withActions(
                            new DialogAction(DialogAction.Type.YES).withHandler(e -> {
                                updateInBackground(new UpdateTask());
                            }),
                            new DialogAction(DialogAction.Type.NO)
                    )
                    .open();
        });
        urlQueryParameters.registerBinder(new FilterUrlQueryParametersBinder());
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
                        }),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    @Install(to = "vectorStoreDataGrid.removeAction", subject = "delegate")
    public void vectorStoreDataGridRemoveActionDelegate(final Collection<VectorStoreEntity> collection) {
        List<VectorStoreEntity> entities = getEntitiesOfTheSameSource(collection.iterator().next());
        vectorStoreRepository.delete(entities);
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