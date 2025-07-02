package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.Parameters;
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
    public Parameters loadActive() {
        List<Parameters> list = dataManager.load(Parameters.class)
                .query("e.active = true")
                .maxResults(1)
                .list();
        if (list.isEmpty()) {
            Parameters parameters = dataManager.create(Parameters.class);
            parameters.setSystemMessage(loadDefaultSystemMessage());
            return parameters;
        } else {
            return list.get(0);
        }
    }

    public Parameters save(Parameters parameters) {
        return dataManager.save(parameters);
    }

    @Override
    public Parameters copy(Parameters parameters) {
        Parameters copy = metadataTools.copy(parameters);
        copy.setId(UuidProvider.createUuidV7());
        copy.setCreatedDate(null);
        copy.setCreatedBy(null);
        copy.setLastModifiedDate(null);
        copy.setLastModifiedBy(null);
        copy.setDescription("Copy of " + parameters.getDescription());
        copy.setActive(false);
        Parameters savedCopy = save(copy);
        return savedCopy;
    }

    @Override
    public String loadDefaultSystemMessage() {
        String message = resources.getResourceAsString("io/jmix/ai/backend/init/system-message.md");
        return message;
    }

    @Override
    public void activate(Parameters parameters) {
        List<Parameters> list = dataManager.load(Parameters.class).all().list();
        for (Parameters entity : list) {
            entity.setActive(entity.equals(parameters));
        }
        dataManager.saveWithoutReload(list);
    }
}
