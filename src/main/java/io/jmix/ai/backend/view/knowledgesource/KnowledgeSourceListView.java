package io.jmix.ai.backend.view.knowledgesource;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.exception.DefaultUiExceptionHandler;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

@Route(value = "knowledge-sources", layout = MainView.class)
@ViewController(id = "KnowledgeSource.list")
@ViewDescriptor(path = "knowledge-source-list-view.xml")
@LookupComponent("knowledgeSourcesDataGrid")
@DialogMode(width = "72em")
public class KnowledgeSourceListView extends StandardListView<KnowledgeSource> {

    @Autowired
    private IngesterManager ingesterManager;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private DefaultUiExceptionHandler defaultUiExceptionHandler;

    @ViewComponent
    private DataGrid<KnowledgeSource> knowledgeSourcesDataGrid;
    @ViewComponent
    private CollectionLoader<KnowledgeSource> knowledgeSourcesDl;
    @ViewComponent
    private MessageBundle messageBundle;

    @Subscribe(id = "runIngestionButton", subject = "clickListener")
    public void onRunIngestionButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeSource source = knowledgeSourcesDataGrid.getSingleSelectedItem();
        if (source == null) {
            notifications.show(messageBundle.getMessage("selectSource"));
            return;
        }
        if (source.getEnabled() == Boolean.FALSE) {
            notifications.show(messageBundle.getMessage("sourceDisabled"));
            return;
        }

        dialogs.createBackgroundTaskDialog(new RunIngestionTask(source))
                .withHeader(messageBundle.getMessage("runDialog.header"))
                .withText(messageBundle.getMessage("runDialog.text"))
                .open();
    }

    private class RunIngestionTask extends BackgroundTask<Integer, String> {

        private final KnowledgeSource source;

        private RunIngestionTask(KnowledgeSource source) {
            super(60, TimeUnit.MINUTES);
            this.source = source;
        }

        @Override
        public String run(TaskLifeCycle<Integer> taskLifeCycle) {
            return ingesterManager.updateBySourceCode(source.getCode());
        }

        @Override
        public void done(String result) {
            dialogs.createMessageDialog()
                    .withHeader(messageBundle.getMessage("runResult.header"))
                    .withContent(new Html("<p>" + result + "</p>"))
                    .open();
            knowledgeSourcesDl.load();
        }

        @Override
        public boolean handleException(Exception ex) {
            defaultUiExceptionHandler.handle(ex);
            return true;
        }
    }
}
