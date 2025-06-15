package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.ParametersEntity;

public interface ParametersRepositoryExt {
    ParametersEntity loadInUse();

    ParametersEntity copy(ParametersEntity parameters);

    String loadDefaultSystemMessage();

    void setInUse(ParametersEntity parametersEntity);
}
