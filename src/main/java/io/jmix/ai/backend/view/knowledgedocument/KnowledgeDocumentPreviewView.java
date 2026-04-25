package io.jmix.ai.backend.view.knowledgedocument;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.vectorstore.KnowledgeDocumentPreviewService;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "knowledge-document-preview", layout = MainView.class)
@ViewController(id = "KnowledgeDocumentPreviewView")
@ViewDescriptor(path = "knowledge-document-preview-view.xml")
public class KnowledgeDocumentPreviewView extends StandardView implements BeforeEnterObserver {

    @Autowired
    private KnowledgeDocumentPreviewService knowledgeDocumentPreviewService;
    @Autowired
    private Notifications notifications;

    @ViewComponent
    private TypedTextField<String> sourceField;
    @ViewComponent
    private TypedTextField<String> documentPathField;
    @ViewComponent
    private JmixTextArea contentField;
    @ViewComponent
    private MessageBundle messageBundle;

    @Subscribe
    public void onInit(InitEvent event) {
        sourceField.setReadOnly(true);
        documentPathField.setReadOnly(true);
        contentField.setReadOnly(true);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        QueryParameters queryParameters = event.getLocation().getQueryParameters();
        String sourceId = queryParameters.getSingleParameter("sourceId").orElse(null);
        String kbCode = queryParameters.getSingleParameter("kb").orElse(null);
        String sourceCode = queryParameters.getSingleParameter("sourceCode").orElse(null);
        String documentPath = queryParameters.getSingleParameter("path").orElse(null);

        if (StringUtils.isBlank(documentPath)) {
            clearFields();
            notifications.show(messageBundle.getMessage("knowledgeDocumentPreviewView.documentNotSpecified"));
            return;
        }

        documentPathField.setTypedValue(documentPath);
        try {
            KnowledgeDocumentPreviewService.PreviewedDocument previewedDocument =
                    knowledgeDocumentPreviewService.loadDocument(sourceId, kbCode, sourceCode, documentPath);
            sourceField.setTypedValue(previewedDocument.sourceName());
            contentField.setValue(previewedDocument.content());
        } catch (RuntimeException e) {
            sourceField.setTypedValue("");
            contentField.setValue("");
            notifications.show(messageBundle.formatMessage("knowledgeDocumentPreviewView.documentLoadFailed", documentPath));
        }
    }

    private void clearFields() {
        sourceField.setTypedValue("");
        documentPathField.setTypedValue("");
        contentField.setValue("");
    }
}
