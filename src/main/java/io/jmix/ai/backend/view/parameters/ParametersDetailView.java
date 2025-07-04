package io.jmix.ai.backend.view.parameters;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.LoadContext;
import io.jmix.core.SaveContext;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.codeeditor.CodeEditor;
import io.jmix.flowui.component.upload.FileUploadField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.upload.event.FileUploadSucceededEvent;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static io.jmix.core.repository.JmixDataRepositoryUtils.extractEntityId;

@Route(value = "parameters/:id", layout = MainView.class)
@ViewController(id = "Parameters.detail")
@ViewDescriptor(path = "parameters-detail-view.xml")
@EditedEntityContainer("parametersEntityDc")
public class ParametersDetailView extends StandardDetailView<Parameters> {

    @Autowired
    private ParametersRepository repository;
    @Autowired
    private Notifications notifications;
    @ViewComponent
    private CodeEditor contentField;

    @Subscribe
    public void onInitEntity(final InitEntityEvent<Parameters> event) {
        Parameters parameters = event.getEntity();
        parameters.setContent(repository.loadDefaultContent());
    }

    @Install(to = "parametersEntityDl", target = Target.DATA_LOADER)
    private Parameters parametersEntityDlLoadDelegate(final LoadContext<Parameters> context) {
        return repository.getById(extractEntityId(context), context.getFetchPlan());
    }

    @Install(target = Target.DATA_CONTEXT)
    private Set<Object> saveDelegate(final SaveContext saveContext) {
        return Set.of(repository.save(getEditedEntity()));
    }

    @Subscribe(id = "copyToClipboardButton", subject = "clickListener")
    public void onCopyToClipboardButtonClick(final ClickEvent<JmixButton> event) {
        UiComponentUtils.copyToClipboard(contentField.getValue())
                .then(jsonValue -> notifications.show("Copied!"));
    }

    @Subscribe("uploadField")
    public void onUploadFieldFileUploadSucceeded(final FileUploadSucceededEvent<FileUploadField> event) {
        byte[] fileContent = event.getSource().getValue();
        if (fileContent == null || fileContent.length == 0) {
            notifications.show("Empty file");
        } else {
            contentField.setValue(new String(fileContent, StandardCharsets.UTF_8));
        }
    }
}