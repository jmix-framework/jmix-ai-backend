package io.jmix.ai.backend.view.knowledgesource;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.KnowledgeDocumentItem;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.SupportsTypedValue;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Route(value = "knowledge-source-documents", layout = MainView.class)
@ViewController(id = "KnowledgeSourceDocumentsView")
@ViewDescriptor(path = "knowledge-source-documents-view.xml")
public class KnowledgeSourceDocumentsView extends StandardView implements BeforeEnterObserver {

    @Autowired
    private KnowledgeSourceDocumentsService knowledgeSourceDocumentsService;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Dialogs dialogs;

    @ViewComponent
    private TypedTextField<String> sourceField;
    @ViewComponent
    private TypedTextField<String> documentsFilterField;
    @ViewComponent
    private CollectionContainer<KnowledgeDocumentItem> documentsDc;
    @ViewComponent
    private DataGrid<KnowledgeDocumentItem> documentsDataGrid;
    @ViewComponent
    private MessageBundle messageBundle;

    private UUID sourceId;
    private KnowledgeSource source;
    private List<KnowledgeDocumentItem> allDocuments = List.of();

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String sourceIdString = event.getLocation().getQueryParameters().getSingleParameter("sourceId").orElse(null);
        if (StringUtils.isBlank(sourceIdString)) {
            notifications.show(messageBundle.getMessage("knowledgeSourceDocumentsView.sourceNotSpecified"));
            documentsDc.setItems(List.of());
            sourceField.setTypedValue("");
            return;
        }

        sourceId = UUID.fromString(sourceIdString);
        source = knowledgeSourceDocumentsService.loadSource(sourceId);
        sourceField.setTypedValue(source.getName());
        reloadDocuments();
    }

    @Subscribe(id = "previewButton", subject = "clickListener")
    public void onPreviewButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeDocumentItem item = documentsDataGrid.getSingleSelectedItem();
        if (item == null) {
            notifications.show(messageBundle.getMessage("knowledgeSourceDocumentsView.selectDocument"));
            return;
        }
        String previewUrl = buildPreviewUrl(item);
        if (previewUrl == null) {
            notifications.show(messageBundle.getMessage("knowledgeSourceDocumentsView.previewNotAvailable"));
            return;
        }
        UI.getCurrent().getPage().open(previewUrl, "_blank");
    }

    @Subscribe(id = "reingestButton", subject = "clickListener")
    public void onReingestButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeDocumentItem item = documentsDataGrid.getSingleSelectedItem();
        if (item == null) {
            notifications.show(messageBundle.getMessage("knowledgeSourceDocumentsView.selectDocument"));
            return;
        }
        knowledgeSourceDocumentsService.reingestDocument(item);
        reloadDocuments();
    }

    @Subscribe(id = "deleteButton", subject = "clickListener")
    public void onDeleteButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeDocumentItem item = documentsDataGrid.getSingleSelectedItem();
        if (item == null) {
            notifications.show(messageBundle.getMessage("knowledgeSourceDocumentsView.selectDocument"));
            return;
        }
        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("knowledgeSourceDocumentsView.deleteConfirm.header"))
                .withText(messageBundle.formatMessage("knowledgeSourceDocumentsView.deleteConfirm.text", item.getDocumentPath()))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e -> {
                            knowledgeSourceDocumentsService.deleteDocument(item);
                            reloadDocuments();
                        }),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    @Subscribe("documentsFilterField")
    public void onDocumentsFilterFieldTypedValueChange(SupportsTypedValue.TypedValueChangeEvent<TypedTextField<String>, String> event) {
        applyDocumentsFilter();
    }

    private void reloadDocuments() {
        allDocuments = knowledgeSourceDocumentsService.loadDocuments(sourceId);
        applyDocumentsFilter();
    }

    private String buildPreviewUrl(KnowledgeDocumentItem item) {
        if (StringUtils.isNotBlank(item.getExternalUrl())) {
            return item.getExternalUrl();
        }
        if (source == null || StringUtils.isBlank(item.getDocumentPath())) {
            return null;
        }
        return "/knowledge-document-preview?sourceId="
                + URLEncoder.encode(Objects.toString(source.getId(), ""), StandardCharsets.UTF_8)
                + "&path="
                + URLEncoder.encode(item.getDocumentPath(), StandardCharsets.UTF_8);
    }

    private void applyDocumentsFilter() {
        String filter = normalizeFilter(documentsFilterField.getTypedValue());
        if (filter == null) {
            documentsDc.setItems(allDocuments);
            return;
        }

        List<KnowledgeDocumentItem> filtered = new ArrayList<>();
        for (KnowledgeDocumentItem item : allDocuments) {
            if (containsIgnoreCase(item.getDocumentPath(), filter)
                    || containsIgnoreCase(item.getDocumentName(), filter)
                    || containsIgnoreCase(item.getDocumentKind(), filter)
                    || containsIgnoreCase(item.getSourceType(), filter)) {
                filtered.add(item);
            }
        }
        documentsDc.setItems(filtered);
    }

    private String normalizeFilter(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return value.toLowerCase();
    }

    private boolean containsIgnoreCase(String value, String filter) {
        return value != null && value.toLowerCase().contains(filter);
    }
}
