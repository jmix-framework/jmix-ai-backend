package io.jmix.ai.backend.view.vectorstore;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.LoadContext;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@Route(value = "vector-store/:id", layout = MainView.class)
@ViewController(id = "VectorStoreEntity.detail")
@ViewDescriptor(path = "vector-store-detail-view.xml")
@EditedEntityContainer("vectorStoreEntityDc")
public class VectorStoreDetailView extends StandardDetailView<VectorStoreEntity> {

    @Autowired
    private VectorStoreRepository vectorStoreRepository;

    @Install(to = "vectorStoreEntityDl", target = Target.DATA_LOADER)
    private VectorStoreEntity loadDelegate(final LoadContext<VectorStoreEntity> loadContext) {
        UUID id = (UUID) loadContext.getId();
        return vectorStoreRepository.load(id);
    }
}
