package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.Parameters;

public interface ParametersRepositoryExt {
    Parameters loadActive();

    Parameters copy(Parameters parameters);

    String loadDefaultSystemMessage();

    void activate(Parameters parameters);
}
