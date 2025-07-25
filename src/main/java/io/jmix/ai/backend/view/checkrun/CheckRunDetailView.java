package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

@Route(value = "check-runs/:id", layout = MainView.class)
@ViewController(id = "CheckRun.detail")
@ViewDescriptor(path = "check-run-detail-view.xml")
@EditedEntityContainer("checkRunDc")
@DialogMode(closeOnEsc = true, closeOnOutsideClick = true)
public class CheckRunDetailView extends StandardDetailView<CheckRun> {

    @Autowired
    private ParametersRepository parametersRepository;

    @ViewComponent
    private EntityPicker<Parameters> parametersPicker;
    @ViewComponent
    private JmixTextArea parametersField;
    @ViewComponent
    private TypedTextField<Double> scriptScoreField;
    @ViewComponent
    private TypedTextField<Double> rougeScoreField;
    @ViewComponent
    private TypedTextField<Double> bertScoreField;
    @ViewComponent
    private TypedTextField<OffsetDateTime> createdDateField;
    @ViewComponent
    private TypedTextField<String> createdByField;
    @ViewComponent
    private HorizontalLayout detailActions;

    @Subscribe
    public void onInitEntity(final InitEntityEvent<CheckRun> event) {
        Parameters parameters = parametersRepository.loadActive();
        event.getEntity().setParameters(parameters.getContent());

        parametersPicker.setValue(parameters);
        parametersPicker.setVisible(true);
        parametersPicker.setRequired(true);
        parametersPicker.focus();

        createdDateField.setVisible(false);
        createdByField.setVisible(false);
        parametersField.setVisible(false);
        scriptScoreField.setVisible(false);
        rougeScoreField.setVisible(false);
        bertScoreField.setVisible(false);

        detailActions.setVisible(true);
    }

    @Subscribe("parametersPicker")
    public void onParametersPickerComponentValueChange(final AbstractField.ComponentValueChangeEvent<EntityPicker<Parameters>, Parameters> event) {
        String parametersYaml = event.getValue() != null ? event.getValue().getContent() : "";
        getEditedEntity().setParameters(parametersYaml);
    }
}