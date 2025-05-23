package io.jmix.ai.backend.view.vectorstore;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.Copier;
import io.jmix.core.EntityStates;
import io.jmix.core.LoadContext;
import io.jmix.core.SaveContext;
import io.jmix.core.entity.EntityValues;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.UUID;

@Route(value = "vector-store/:id", layout = MainView.class)
@ViewController(id = "VectorStoreEntity.detail")
@ViewDescriptor(path = "vector-store-detail-view.xml")
@EditedEntityContainer("vectorStoreEntityDc")
public class VectorStoreDetailView extends StandardDetailView<VectorStoreEntity> {

    @Autowired
    private Copier copier;
    @Autowired
    private EntityStates entityStates;
    @Autowired
    private VectorStoreRepository vectorStoreRepository;

    @Install(to = "vectorStoreEntityDl", target = Target.DATA_LOADER)
    private VectorStoreEntity loadDelegate(final LoadContext<VectorStoreEntity> loadContext) {
        UUID id = (UUID) loadContext.getId();
        return vectorStoreRepository.load(id);
    }

    @Install(target = Target.DATA_CONTEXT)
    private Set<Object> saveDelegate(final SaveContext saveContext) {
        VectorStoreEntity entity = getEditedEntity();
        // Make a copy and save it. Copying isolates the view from possible changes of the saved entity.
        VectorStoreEntity saved = save(copier.copy(entity));
        // If the new entity ID is assigned by the storage, set the ID to the original entity instance
        // to let the framework match the saved instance with the original one.
        if (EntityValues.getId(entity) == null) {
            EntityValues.setId(entity, EntityValues.getId(saved));
        }
        // Set the returned entity to the not-new state.
        entityStates.setNew(saved, false);
        return Set.of(saved);
    }

    private VectorStoreEntity save(VectorStoreEntity entity) {
        // Here you can save the entity to an external storage and return the saved instance.
        return entity;
    }
}
