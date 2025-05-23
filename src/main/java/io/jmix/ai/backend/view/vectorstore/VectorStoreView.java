package io.jmix.ai.backend.view.vectorstore;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.vectorstore.VectorStoreUpdateManager;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.LoadContext;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.SupportsTypedValue;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Route(value = "vector-store", layout = MainView.class)
@ViewController(id = "VectorStoreView")
@ViewDescriptor(path = "vector-store-view.xml")
@LookupComponent("vectorStoreDataGrid")
@DialogMode(width = "64em")
public class VectorStoreView extends StandardListView<VectorStoreEntity> {

    @Autowired
    private VectorStoreUpdateManager vectorStoreUpdateManager;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private VectorStoreRepository vectorStoreRepository;
    @ViewComponent
    private CollectionLoader<VectorStoreEntity> vectorStoreDl;
    @ViewComponent
    private TypedTextField<String> filterField;
    @Autowired
    private Notifications notifications;

    @Install(to = "vectorStoreDl", target = Target.DATA_LOADER)
    private List<VectorStoreEntity> vectorStoreDlLoadDelegate(final LoadContext<VectorStoreEntity> loadContext) {
        return vectorStoreRepository.loadList(filterField.getTypedValue());
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
        dialogs.createOptionDialog()
                .withHeader("Confirmation")
                .withText("Update all vector store data?")
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e ->
                                updateInBackground()),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    private void updateInBackground() {
        dialogs.createBackgroundTaskDialog(new UpdateTask())
                .withHeader("Updating vector store data")
                .withText("Please wait...")
                .open();
    }

    @Subscribe(id = "removeAllButton", subject = "clickListener")
    public void onRemoveAllButtonClick(final ClickEvent<JmixButton> event) {
        dialogs.createOptionDialog()
                .withHeader("Warning")
                .withText("Remove all vector store data?")
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e -> {
                            vectorStoreRepository.deleteAll();
                            vectorStoreDl.load();
                        }),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    @Install(to = "vectorStoreDataGrid.removeAction", subject = "delegate")
    public void vectorStoreDataGridRemoveActionDelegate(final Collection<VectorStoreEntity> collection) {
        vectorStoreRepository.delete(collection);
    }

    private class UpdateTask extends BackgroundTask<Integer, String> {

        protected UpdateTask() {
            super(60, TimeUnit.MINUTES);
        }

        @Override
        public String run(TaskLifeCycle<Integer> taskLifeCycle) throws Exception {
            return vectorStoreUpdateManager.update();
        }

        @Override
        public void done(String result) {
            dialogs.createMessageDialog()
                    .withContent(new Html("<p>" + result + "</p>"))
                    .withHeader("Update result")
                    .open();
            vectorStoreDl.load();
        }
    }
}