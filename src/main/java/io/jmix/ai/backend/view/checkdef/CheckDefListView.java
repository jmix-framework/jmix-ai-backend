package io.jmix.ai.backend.view.checkdef;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.core.EntityImportExport;
import io.jmix.core.EntityImportPlans;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.upload.FileUploadField;
import io.jmix.flowui.download.Downloader;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.upload.event.FileUploadSucceededEvent;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;


@Route(value = "check-defs", layout = MainView.class)
@ViewController(id = "CheckDef.list")
@ViewDescriptor(path = "check-def-list-view.xml")
@LookupComponent("checkDefsDataGrid")
@DialogMode(width = "64em")
public class CheckDefListView extends StandardListView<CheckDef> {

    @Autowired
    private DataManager dataManager;
    @ViewComponent
    private DataGrid<CheckDef> checkDefsDataGrid;
    @ViewComponent
    private CollectionContainer<CheckDef> checkDefsDc;
    @ViewComponent
    private CollectionLoader<CheckDef> checkDefsDl;
    @ViewComponent
    private MessageBundle messageBundle;
    @Autowired
    private EntityImportExport entityImportExport;
    @Autowired
    private EntityImportPlans entityImportPlans;
    @Autowired
    private Downloader downloader;
    @Autowired
    private Notifications notifications;

    @Subscribe(id = "toggleActiveButton", subject = "clickListener")
    public void onToggleActiveButtonClick(final ClickEvent<JmixButton> event) {
        Set<CheckDef> selectedItems = checkDefsDataGrid.getSelectedItems();
        for (CheckDef checkDef : selectedItems) {
            checkDef.setActive(!BooleanUtils.toBoolean(checkDef.getActive()));
            CheckDef saved = dataManager.save(checkDef);
            checkDefsDc.replaceItem(saved);
        }
    }

    @Subscribe("checkDefsDataGrid.jsonExportAction")
    public void onCheckDefsDataGridJsonExportAction(final ActionPerformedEvent event) {
        Set<CheckDef> selectedItems = checkDefsDataGrid.getSelectedItems();
        if (selectedItems.isEmpty()) {
            notifications.show(messageBundle.getMessage("jsonExport.selectItems"));
            return;
        }
        String json = entityImportExport.exportEntitiesToJSON(selectedItems);
        downloader.download(json.getBytes(StandardCharsets.UTF_8), "check-defs.json");
    }

    @Subscribe("jsonImportUploadField")
    public void onJsonImportUploadFieldFileUploadSucceeded(final FileUploadSucceededEvent<FileUploadField> event) {
        byte[] fileContent = event.getSource().getValue();
        if (fileContent == null || fileContent.length == 0) {
            notifications.show(messageBundle.getMessage("jsonImport.emptyFile"));
            return;
        }

        String json = new String(fileContent, StandardCharsets.UTF_8);
        try {
            Collection<Object> imported = entityImportExport.importEntitiesFromJson(
                    json,
                    entityImportPlans.builder(CheckDef.class).addLocalProperties().build()
            );
            checkDefsDl.load();
            notifications.show(messageBundle.formatMessage("jsonImport.success", imported.size()));
        } catch (Exception exception) {
            notifications.show(messageBundle.formatMessage("jsonImport.failure", exception.getMessage()));
        }
    }
}
