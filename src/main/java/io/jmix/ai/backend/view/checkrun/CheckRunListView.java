package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckRunner;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.Id;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;


@Route(value = "check-runs", layout = MainView.class)
@ViewController(id = "CheckRun.list")
@ViewDescriptor(path = "check-run-list-view.xml")
@LookupComponent("checkRunsDataGrid")
@DialogMode(width = "64em")
public class CheckRunListView extends StandardListView<CheckRun> {

    @Autowired
    private CheckRunner checkRunner;
    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private DatatypeFormatter datatypeFormatter;
    @Autowired
    private UiComponents uiComponents;
    @ViewComponent
    private DataGrid<CheckRun> checkRunsDataGrid;
    @ViewComponent
    private CollectionLoader<CheckRun> checkRunsDl;
    @Autowired
    private Dialogs dialogs;

    @Install(to = "checkRunsDataGrid.createAction", subject = "afterSaveHandler")
    private void checkRunsDataGridCreateActionAfterSaveHandler(final CheckRun checkRun) {
        dialogs.createBackgroundTaskDialog(
                        new BackgroundTask<Integer, Void>(3600, this) {
                            @Override
                            public Void run(TaskLifeCycle<Integer> taskLifeCycle) throws Exception {
                                checkRunner.runChecks(Id.of(checkRun));
                                return null;
                            }

                            @Override
                            public void done(Void result) {
                                checkRunsDl.load();
                            }
                        }
                )
                .withHeader("Checks are running")
                .withText("Please wait...")
//                .withTotal(10)
//                .withShowProgressInPercentage(true)
                .withCancelAllowed(true)
                .open();
    }

    @Supply(to = "checksDataGrid.link", subject = "renderer")
    private Renderer<Check> checksDataGridLinkRenderer() {
        return new ComponentRenderer<>(check -> {
            Anchor anchor = uiComponents.create(Anchor.class);
            anchor.setHref("/checks/" + check.getId() + "?mode=readonly");
            anchor.setTarget("_blank");
            anchor.add(new Icon(VaadinIcon.EXTERNAL_LINK));
            return anchor;
        });
    }
}