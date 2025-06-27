package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.ParametersEntity;

public interface ParametersRepositoryExt {
    ParametersEntity loadActive();

    ParametersEntity copy(ParametersEntity parameters);

    String loadDefaultSystemMessage();

    void activate(ParametersEntity parametersEntity);
}
