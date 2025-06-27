package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.ParametersEntity;
import io.jmix.core.MetadataTools;
import io.jmix.core.Resources;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.UuidProvider;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ParametersRepositoryExtImpl implements ParametersRepositoryExt {

    private final UnconstrainedDataManager dataManager;
    private final Resources resources;
    private final MetadataTools metadataTools;

    public ParametersRepositoryExtImpl(UnconstrainedDataManager dataManager, Resources resources, MetadataTools metadataTools) {
        this.dataManager = dataManager;
        this.resources = resources;
        this.metadataTools = metadataTools;
    }

    @Override
    public ParametersEntity loadActive() {
        List<ParametersEntity> list = dataManager.load(ParametersEntity.class)
                .query("e.active = true")
                .maxResults(1)
                .list();
        if (list.isEmpty()) {
            ParametersEntity parametersEntity = dataManager.create(ParametersEntity.class);
            parametersEntity.setSystemMessage(loadDefaultSystemMessage());
            return parametersEntity;
        } else {
            return list.get(0);
        }
    }

    public ParametersEntity save(ParametersEntity parametersEntity) {
        return dataManager.save(parametersEntity);
    }

    @Override
    public ParametersEntity copy(ParametersEntity parameters) {
        ParametersEntity copy = metadataTools.copy(parameters);
        copy.setId(UuidProvider.createUuidV7());
        copy.setCreatedDate(null);
        copy.setCreatedBy(null);
        copy.setLastModifiedDate(null);
        copy.setLastModifiedBy(null);
        copy.setDescription("Copy of " + parameters.getDescription());
        copy.setActive(false);
        ParametersEntity savedCopy = save(copy);
        return savedCopy;
    }

    @Override
    public String loadDefaultSystemMessage() {
        String message = resources.getResourceAsString("io/jmix/ai/backend/init/system-message.md");
        return message;
    }

    @Override
    public void activate(ParametersEntity parametersEntity) {
        List<ParametersEntity> list = dataManager.load(ParametersEntity.class).all().list();
        for (ParametersEntity entity : list) {
            entity.setActive(entity.equals(parametersEntity));
        }
        dataManager.saveWithoutReload(list);
    }
}
