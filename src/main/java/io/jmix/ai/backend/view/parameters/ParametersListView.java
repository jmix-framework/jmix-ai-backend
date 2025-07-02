package io.jmix.ai.backend.view.parameters;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.chat.ParametersRepository;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.LoadContext;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

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

    @Install(to = "parametersEntitiesDl", target = Target.DATA_LOADER)
    private List<Parameters> parametersEntitiesDlLoadDelegate(final LoadContext<Parameters> context) {
        return repository.findAll(Pageable.unpaged(), buildRepositoryContext(context)).getContent();
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