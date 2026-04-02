package io.jmix.ai.backend.view.checkrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.CheckRunStatus;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Route(value = "check-runs/:id", layout = MainView.class)
@ViewController(id = "CheckRun.detail")
@ViewDescriptor(path = "check-run-detail-view.xml")
@EditedEntityContainer("checkRunDc")
@DialogMode(closeOnEsc = true, closeOnOutsideClick = true)
public class CheckRunDetailView extends StandardDetailView<CheckRun> {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final DateTimeFormatter EXPERIMENT_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private boolean createMode;

    @Autowired
    private ParametersRepository parametersRepository;

    @ViewComponent
    private EntityPicker<Parameters> parametersPicker;
    @ViewComponent
    private JmixTextArea parametersField;
    @ViewComponent
    private TypedTextField<Double> scoreField;
    @ViewComponent
    private TypedTextField<OffsetDateTime> createdDateField;
    @ViewComponent
    private TypedTextField<String> createdByField;
    @ViewComponent
    private HorizontalLayout detailActions;
    @ViewComponent
    private VerticalLayout resultSection;
    @ViewComponent
    private Checkbox goldenOnlyField;

    @Subscribe
    public void onInitEntity(final InitEntityEvent<CheckRun> event) {
        createMode = true;
        Parameters parameters = parametersRepository.loadActive(ParametersTargetType.CHAT);
        event.getEntity().setParameters(parameters.getContent());
        event.getEntity().setStatus(CheckRunStatus.NEW);
        event.getEntity().setGoldenOnly(Boolean.TRUE);
        event.getEntity().setExperimentKey(generateExperimentKey(parameters.getContent(), Boolean.TRUE));

        parametersPicker.setValue(parameters);
        parametersPicker.setVisible(true);
        parametersPicker.setRequired(true);
        parametersPicker.focus();
        goldenOnlyField.setValue(Boolean.TRUE);
        applyCreateMode();
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        if (createMode) {
            applyCreateMode();
        } else {
            applyResultMode();
        }
    }

    @Subscribe("parametersPicker")
    public void onParametersPickerComponentValueChange(final AbstractField.ComponentValueChangeEvent<EntityPicker<Parameters>, Parameters> event) {
        String parametersYaml = event.getValue() != null ? event.getValue().getContent() : "";
        getEditedEntity().setParameters(parametersYaml);
        getEditedEntity().setExperimentKey(generateExperimentKey(parametersYaml, Boolean.TRUE.equals(goldenOnlyField.getValue())));
    }

    @Subscribe("goldenOnlyField")
    public void onGoldenOnlyFieldValueChange(final AbstractField.ComponentValueChangeEvent<Checkbox, Boolean> event) {
        getEditedEntity().setExperimentKey(generateExperimentKey(getEditedEntity().getParameters(), Boolean.TRUE.equals(event.getValue())));
    }

    private String generateExperimentKey(String parametersYaml, boolean goldenOnly) {
        String modelName = extractModelName(parametersYaml);
        String modelSlug = slugify(modelName != null ? modelName : "chat-model");
        String subset = goldenOnly ? "gold" : "all";
        String date = EXPERIMENT_DATE_FORMATTER.format(LocalDate.now());
        return "%s-local-%s-%s".formatted(modelSlug, subset, date);
    }

    private String extractModelName(String parametersYaml) {
        if (parametersYaml == null || parametersYaml.isBlank()) {
            return null;
        }
        try {
            JsonNode root = YAML_MAPPER.readTree(parametersYaml);
            JsonNode modelNode = root.path("model").path("name");
            if (!modelNode.isTextual()) {
                return null;
            }
            String rawName = modelNode.asText();
            int slashIndex = rawName.lastIndexOf('/');
            return slashIndex >= 0 ? rawName.substring(slashIndex + 1) : rawName;
        } catch (Exception e) {
            return null;
        }
    }

    private String slugify(String value) {
        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\.gguf$", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }

    private void applyCreateMode() {
        resultSection.setVisible(false);
        detailActions.setVisible(true);
        createdDateField.setVisible(false);
        createdByField.setVisible(false);
        parametersField.setVisible(false);
        scoreField.setVisible(false);
    }

    private void applyResultMode() {
        resultSection.setVisible(true);
        detailActions.setVisible(false);
        parametersPicker.setVisible(false);
        goldenOnlyField.setReadOnly(true);
        createdDateField.setVisible(true);
        createdByField.setVisible(true);
        parametersField.setVisible(true);
        scoreField.setVisible(true);
    }
}
