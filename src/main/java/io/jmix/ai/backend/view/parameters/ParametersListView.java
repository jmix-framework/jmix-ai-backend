package io.jmix.ai.backend.view.parameters;

import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.LoadContext;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.action.list.CreateAction;
import io.jmix.flowui.app.inputdialog.DialogActions;
import io.jmix.flowui.app.inputdialog.DialogOutcome;
import io.jmix.flowui.app.inputdialog.InputParameter;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static io.jmix.core.repository.JmixDataRepositoryUtils.buildRepositoryContext;

@Route(value = "parameters", layout = MainView.class)
@ViewController(id = "Parameters.list")
@ViewDescriptor(path = "parameters-list-view.xml")
@LookupComponent("parametersEntitiesDataGrid")
@DialogMode(width = "64em")
public class ParametersListView extends StandardListView<Parameters> {

    @ViewComponent
    private DataGrid<Parameters> parametersEntitiesDataGrid;
    @ViewComponent
    private CollectionContainer<Parameters> parametersEntitiesDc;
    @Autowired
    private ParametersRepository repository;

    @ViewComponent
    private CollectionLoader<Parameters> parametersEntitiesDl;
    @Autowired
    private Dialogs dialogs;
    @ViewComponent("parametersEntitiesDataGrid.createAction")
    private CreateAction<Parameters> parametersEntitiesDataGridCreateAction;

    @Install(to = "parametersEntitiesDl", target = Target.DATA_LOADER)
    private List<Parameters> parametersEntitiesDlLoadDelegate(final LoadContext<Parameters> context) {
        return repository.findAll(Pageable.unpaged(), buildRepositoryContext(context)).getContent();
    }

    @Subscribe("parametersEntitiesDataGrid.createAction")
    public void onParametersEntitiesDataGridCreateAction(final ActionPerformedEvent event) {
        dialogs.createInputDialog(this)
                .withHeader("Choose target type")
                .withParameters(
                        InputParameter.enumParameter("targetType", ParametersTargetType.class).withLabel("Target type").withRequired(true)
                )
                .withActions(DialogActions.OK_CANCEL)
                .withCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(DialogOutcome.OK)) {
                        ParametersTargetType targetType = Objects.requireNonNull(closeEvent.getValue("targetType"));
                        parametersEntitiesDataGridCreateAction.setQueryParametersProvider(() ->
                                QueryParameters.of("targetType", targetType.toString()));
                        parametersEntitiesDataGridCreateAction.execute();
                    }
                })
                .open();
    }

    @Install(to = "parametersEntitiesDataGrid.removeAction", subject = "delegate")
    private void parametersEntitiesDataGridRemoveActionDelegate(final Collection<Parameters> collection) {
        repository.deleteAll(collection);
    }

    @Subscribe("parametersEntitiesDataGrid.copyAction")
    public void onParametersEntitiesDataGridCopyAction(final ActionPerformedEvent event) {
        Parameters parameters = parametersEntitiesDataGrid.getSingleSelectedItem();
        if (parameters == null)
            return;

        Parameters copy = repository.copy(parameters);
        parametersEntitiesDc.getMutableItems().add(0, copy);

        parametersEntitiesDataGrid.select(copy);
    }

    @Subscribe("parametersEntitiesDataGrid.activateAction")
    public void onParametersEntitiesDataGridActivateAction(final ActionPerformedEvent event) {
        Parameters parameters = parametersEntitiesDataGrid.getSingleSelectedItem();
        if (parameters == null)
            return;

        repository.activate(parameters);
        parametersEntitiesDl.load();

        parametersEntitiesDataGrid.select(parameters);
    }
}