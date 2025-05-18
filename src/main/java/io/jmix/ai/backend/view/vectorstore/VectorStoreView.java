package io.jmix.ai.backend.view.vectorstore;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.VsLoaderManager;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;


@Route(value = "vector-store", layout = MainView.class)
@ViewController(id = "VectorStoreView")
@ViewDescriptor(path = "vector-store-view.xml")
@LookupComponent("vectorStoreEntitiesDataGrid")
@DialogMode(width = "64em")
public class VectorStoreView extends StandardListView<VectorStoreEntity> {

    @ViewComponent
    private CollectionLoader<VectorStoreEntity> vectorStoreEntitiesDl;
    @Autowired
    private VsLoaderManager vsLoaderManager;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private DataManager dataManager;
    @Autowired @Qualifier("pgvectorJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    @Subscribe(id = "loadButton", subject = "clickListener")
    public void onLoadButtonClick(final ClickEvent<JmixButton> event) {
        dialogs.createOptionDialog()
                .withHeader("Confirmation")
                .withText("Load vector store data?")
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e -> {
                            vsLoaderManager.load();
                            vectorStoreEntitiesDl.load();
                        }),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    @Subscribe(id = "removeAllButton", subject = "clickListener")
    public void onRemoveAllButtonClick(final ClickEvent<JmixButton> event) {
        dialogs.createOptionDialog()
                .withHeader("Warning")
                .withText("Remove all vector store records?")
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(e -> {
                            removeAll();
                            vectorStoreEntitiesDl.load();
                        }),
                        new DialogAction(DialogAction.Type.NO)
                )
                .open();
    }

    private void removeAll() {
        jdbcTemplate.update("delete from vector_store");
    }
}