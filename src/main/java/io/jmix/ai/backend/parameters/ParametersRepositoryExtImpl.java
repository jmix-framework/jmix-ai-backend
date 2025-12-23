package io.jmix.ai.backend.parameters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.core.MetadataTools;
import io.jmix.core.Resources;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.UuidProvider;
import org.springframework.lang.Nullable;
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
    public Parameters loadActive(ParametersTargetType type) {
        List<Parameters> list = dataManager.load(Parameters.class)
                .query("e.active = true and e.targetType = :type")
                .parameter("type", type.getId())
                .maxResults(1)
                .list();
        if (list.isEmpty()) {
            Parameters parameters = dataManager.create(Parameters.class);
            parameters.setTargetType(type);
            parameters.setContent(loadDefaultContent(type));
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
    public String loadDefaultContent(ParametersTargetType type) {
        String fileName = switch (type) {
            case CHAT -> "default-params-chat.yml";
            case SEARCH -> "default-params-search.yml";
        };
        return resources.getResourceAsString("io/jmix/ai/backend/init/" + fileName);
    }

    @Override
    public void activate(Parameters parameters) {
        // Deactivate all parameters of this type except the one being activated
        List<Parameters> toDeactivate = dataManager.load(Parameters.class)
                .query("e.targetType = :type and e.active = true and e.id <> :id")
                .parameter("type", parameters.getTargetType().getId())
                .parameter("id", parameters.getId())
                .list();
        for (Parameters entity : toDeactivate) {
            entity.setActive(false);
        }
        if (!toDeactivate.isEmpty()) {
            dataManager.saveWithoutReload(toDeactivate);
        }

        // Activate the specified parameter
        parameters.setActive(true);
        dataManager.saveWithoutReload(parameters);
    }

    @Override
    public ParametersReader getReader(Parameters parameters) {
        return new ParametersReader(getObjectMap(parameters));
    }

    @Override
    public ParametersReader getReader(@Nullable String parametersYaml) {
        return new ParametersReader(getObjectMap(parametersYaml));
    }

    private Map<String, Object> getObjectMap(String parametersYaml) {
        if (parametersYaml == null) {
            return Map.of();
        }
        try {
            //noinspection unchecked
            Map<String, Object> data = objectMapper.readValue(parametersYaml, Map.class);

            String resourcePath = (String) data.get("resourcePath");
            if (resourcePath != null) {
                String resourceContent = resources.getResourceAsString(resourcePath);
                //noinspection unchecked
                Map<String, Object> resourceData = objectMapper.readValue(resourceContent, Map.class);
                return resourceData;
            } else {
                return data;
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> getObjectMap(Parameters parameters) {
        return getObjectMap(parameters.getContent());
    }
}
