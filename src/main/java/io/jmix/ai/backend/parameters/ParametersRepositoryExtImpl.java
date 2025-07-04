package io.jmix.ai.backend.parameters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.core.MetadataTools;
import io.jmix.core.Resources;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.UuidProvider;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ParametersRepositoryExtImpl implements ParametersRepositoryExt {

    private final UnconstrainedDataManager dataManager;
    private final Resources resources;
    private final MetadataTools metadataTools;
    private final ObjectMapper objectMapper;

    public ParametersRepositoryExtImpl(UnconstrainedDataManager dataManager, Resources resources, MetadataTools metadataTools) {
        this.dataManager = dataManager;
        this.resources = resources;
        this.metadataTools = metadataTools;
        objectMapper = new ObjectMapper(new YAMLFactory());
    }

    @Override
    public Parameters loadActive() {
        List<Parameters> list = dataManager.load(Parameters.class)
                .query("e.active = true")
                .maxResults(1)
                .list();
        if (list.isEmpty()) {
            Parameters parameters = dataManager.create(Parameters.class);
            parameters.setContent(loadDefaultContent());
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
        copy.setActive(false);
        // todo modify description
        Parameters savedCopy = save(copy);
        return savedCopy;
    }

    @Override
    public String loadDefaultContent() {
        String message = resources.getResourceAsString("io/jmix/ai/backend/init/default-params.yml");
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

    @Override
    public ParametersReader getReader(Parameters parameters) {
        return new ParametersReader(getObjectMap(parameters)) ;
    }

    private Map<String, Object> getObjectMap(Parameters parameters) {
        if (parameters.getContent() == null) {
            return Map.of();
        }
        try {
            //noinspection unchecked
            Map<String, Object> data = objectMapper.readValue(parameters.getContent(), Map.class);
            return data;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
