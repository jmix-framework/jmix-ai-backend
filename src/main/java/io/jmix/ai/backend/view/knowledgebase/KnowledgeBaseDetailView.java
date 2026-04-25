package io.jmix.ai.backend.view.knowledgebase;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.IngestionJob;
import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.entity.KnowledgeDocumentItem;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.ai.backend.vectorstore.business.BusinessDocumentsSupport;
import io.jmix.ai.backend.vectorstore.business.BusinessDocumentsUploadService;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.ai.backend.view.knowledgesource.KnowledgeSourceDocumentsService;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.SupportsTypedValue;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.upload.FileUploadField;
import io.jmix.flowui.exception.DefaultUiExceptionHandler;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.upload.event.FileUploadSucceededEvent;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Route(value = "knowledge-bases/:id", layout = MainView.class)
@ViewController(id = "KnowledgeBase.detail")
@ViewDescriptor(path = "knowledge-base-detail-view.xml")
@EditedEntityContainer("knowledgeBaseDc")
public class KnowledgeBaseDetailView extends StandardDetailView<KnowledgeBase> {

    @Autowired
    private KnowledgeBaseWorkspaceService knowledgeBaseWorkspaceService;
    @Autowired
    private KnowledgeSourceDocumentsService knowledgeSourceDocumentsService;
    @Autowired
    private IngesterManager ingesterManager;
    @Autowired
    private BusinessDocumentsUploadService businessDocumentsUploadService;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private DefaultUiExceptionHandler defaultUiExceptionHandler;

    @ViewComponent
    private CollectionContainer<KnowledgeSource> knowledgeSourcesDc;
    @ViewComponent
    private CollectionContainer<KnowledgeDocumentItem> documentsDc;
    @ViewComponent
    private CollectionContainer<IngestionJob> ingestionJobsDc;
    @ViewComponent
    private DataGrid<KnowledgeSource> knowledgeSourcesDataGrid;
    @ViewComponent
    private DataGrid<KnowledgeDocumentItem> documentsDataGrid;
    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private FileUploadField uploadBusinessDocumentField;
    @ViewComponent
    private TypedTextField<String> sourcesFilterField;
    @ViewComponent
    private TypedTextField<String> documentsFilterField;

    private List<KnowledgeSource> allSources = List.of();
    private List<KnowledgeDocumentItem> allDocuments = List.of();

    @Subscribe
    public void onInit(InitEvent event) {
        uploadBusinessDocumentField.setAcceptedFileTypes(BusinessDocumentsSupport.INCLUDED_EXTENSIONS.toArray(String[]::new));
    }

    @Subscribe
    public void onReady(ReadyEvent event) {
        reloadWorkspace();
    }

    @Subscribe(id = "browseSourceDocumentsButton", subject = "clickListener")
    public void onBrowseSourceDocumentsButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeSource source = knowledgeSourcesDataGrid.getSingleSelectedItem();
        if (source == null) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.selectSource"));
            return;
        }
        UI.getCurrent().navigate("knowledge-source-documents?sourceId=" + source.getId());
    }

    @Subscribe(id = "runSourceIngestionButton", subject = "clickListener")
    public void onRunSourceIngestionButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeSource source = requireSelectedSource();
        if (source == null) {
            return;
        }

        dialogs.createBackgroundTaskDialog(new RunIngestionTask(source))
                .withHeader(messageBundle.getMessage("knowledgeBaseDetailView.runDialog.header"))
                .withText(messageBundle.getMessage("knowledgeBaseDetailView.runDialog.text"))
                .open();
    }

    @Subscribe("uploadBusinessDocumentField")
    public void onUploadBusinessDocumentFieldFileUploadSucceeded(FileUploadSucceededEvent<FileUploadField> event) {
        KnowledgeSource source = knowledgeSourcesDataGrid.getSingleSelectedItem();
        if (source == null) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.selectSource"));
            return;
        }
        if (!BusinessDocumentsSupport.isBusinessDocumentsSource(source)) {
            notifications.show(messageBundle.getMessage("uploadBusinessDocument.unsupportedSource"));
            return;
        }

        byte[] fileContent = event.getSource().getValue();
        if (fileContent == null || fileContent.length == 0) {
            notifications.show(messageBundle.getMessage("uploadBusinessDocument.emptyFile"));
            return;
        }

        try {
            String storedPath = businessDocumentsUploadService.upload(source, event.getFileName(), fileContent);
            dialogs.createBackgroundTaskDialog(new RunIngestionTask(source))
                    .withHeader(messageBundle.getMessage("uploadBusinessDocument.runDialog.header"))
                    .withText(messageBundle.formatMessage("uploadBusinessDocument.runDialog.text", storedPath))
                    .open();
        } catch (RuntimeException ex) {
            notifications.show(messageBundle.formatMessage("uploadBusinessDocument.failure", ex.getMessage()));
        }
    }

    @Subscribe(id = "previewDocumentButton", subject = "clickListener")
    public void onPreviewDocumentButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeDocumentItem item = documentsDataGrid.getSingleSelectedItem();
        if (item == null) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.selectDocument"));
            return;
        }
        String previewUrl = buildPreviewUrl(item);
        if (previewUrl == null) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.previewNotAvailable"));
            return;
        }
        UI.getCurrent().getPage().open(previewUrl, "_blank");
    }

    @Subscribe(id = "reingestDocumentButton", subject = "clickListener")
    public void onReingestDocumentButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeDocumentItem item = documentsDataGrid.getSingleSelectedItem();
        if (item == null) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.selectDocument"));
            return;
        }
        knowledgeSourceDocumentsService.reingestDocument(item);
        reloadWorkspace();
    }

    @Subscribe(id = "deleteDocumentButton", subject = "clickListener")
    public void onDeleteDocumentButtonClick(ClickEvent<JmixButton> event) {
        KnowledgeDocumentItem item = documentsDataGrid.getSingleSelectedItem();
        if (item == null) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.selectDocument"));
            return;
        }
        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("knowledgeBaseDetailView.deleteConfirm.header"))
                .withText(messageBundle.formatMessage("knowledgeBaseDetailView.deleteConfirm.text", item.getDocumentPath()))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e -> {
                            knowledgeSourceDocumentsService.deleteDocument(item);
                            reloadWorkspace();
                        }),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    @Subscribe("sourcesFilterField")
    public void onSourcesFilterFieldTypedValueChange(SupportsTypedValue.TypedValueChangeEvent<TypedTextField<String>, String> event) {
        applySourcesFilter();
    }

    @Subscribe("documentsFilterField")
    public void onDocumentsFilterFieldTypedValueChange(SupportsTypedValue.TypedValueChangeEvent<TypedTextField<String>, String> event) {
        applyDocumentsFilter();
    }

    private void reloadWorkspace() {
        KnowledgeBase knowledgeBase = getEditedEntityOrNull();
        if (knowledgeBase == null || knowledgeBase.getId() == null) {
            allSources = List.of();
            allDocuments = List.of();
            knowledgeSourcesDc.setItems(allSources);
            documentsDc.setItems(allDocuments);
            ingestionJobsDc.setItems(List.of());
            return;
        }

        allSources = knowledgeBaseWorkspaceService.loadSources(knowledgeBase.getId());
        allDocuments = knowledgeBaseWorkspaceService.loadDocuments(knowledgeBase.getId());
        applySourcesFilter();
        applyDocumentsFilter();
        ingestionJobsDc.setItems(knowledgeBaseWorkspaceService.loadJobs(knowledgeBase.getId()));
    }

    private KnowledgeSource requireSelectedSource() {
        KnowledgeSource source = knowledgeSourcesDataGrid.getSingleSelectedItem();
        if (source == null) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.selectSource"));
            return null;
        }
        if (source.getEnabled() == Boolean.FALSE) {
            notifications.show(messageBundle.getMessage("knowledgeBaseDetailView.sourceDisabled"));
            return null;
        }
        return source;
    }

    private String buildPreviewUrl(KnowledgeDocumentItem item) {
        if (item.getExternalUrl() != null && !item.getExternalUrl().isBlank()) {
            return item.getExternalUrl();
        }
        if (item.getKnowledgeSourceId() == null || item.getDocumentPath() == null || item.getDocumentPath().isBlank()) {
            return null;
        }
        return "/knowledge-document-preview?sourceId="
                + URLEncoder.encode(Objects.toString(item.getKnowledgeSourceId(), ""), StandardCharsets.UTF_8)
                + "&path="
                + URLEncoder.encode(item.getDocumentPath(), StandardCharsets.UTF_8);
    }

    private void applySourcesFilter() {
        String filter = normalizeFilter(sourcesFilterField.getTypedValue());
        if (filter == null) {
            knowledgeSourcesDc.setItems(allSources);
            return;
        }

        List<KnowledgeSource> filtered = new ArrayList<>();
        for (KnowledgeSource source : allSources) {
            if (containsIgnoreCase(source.getName(), filter)
                    || containsIgnoreCase(source.getCode(), filter)
                    || containsIgnoreCase(Objects.toString(source.getSourceType(), null), filter)
                    || containsIgnoreCase(source.getLocation(), filter)) {
                filtered.add(source);
            }
        }
        knowledgeSourcesDc.setItems(filtered);
    }

    private void applyDocumentsFilter() {
        String filter = normalizeFilter(documentsFilterField.getTypedValue());
        if (filter == null) {
            documentsDc.setItems(allDocuments);
            return;
        }

        List<KnowledgeDocumentItem> filtered = new ArrayList<>();
        for (KnowledgeDocumentItem item : allDocuments) {
            if (containsIgnoreCase(item.getSourceName(), filter)
                    || containsIgnoreCase(item.getDocumentPath(), filter)
                    || containsIgnoreCase(item.getDocumentName(), filter)
                    || containsIgnoreCase(item.getDocumentKind(), filter)
                    || containsIgnoreCase(item.getSourceType(), filter)) {
                filtered.add(item);
            }
        }
        documentsDc.setItems(filtered);
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.toLowerCase();
    }

    private boolean containsIgnoreCase(String value, String filter) {
        return value != null && value.toLowerCase().contains(filter);
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
                    .withHeader(messageBundle.getMessage("knowledgeBaseDetailView.runResult.header"))
                    .withText(result)
                    .open();
            reloadWorkspace();
        }

        @Override
        public boolean handleException(Exception ex) {
            defaultUiExceptionHandler.handle(ex);
            return true;
        }
    }
}
