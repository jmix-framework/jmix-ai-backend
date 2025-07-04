package io.jmix.ai.backend.parameters;

import io.jmix.ai.backend.entity.Parameters;

public interface ParametersRepositoryExt {
    Parameters loadActive();

    Parameters copy(Parameters parameters);

    String loadDefaultContent();

    void activate(Parameters parameters);

    ParametersReader getReader(Parameters parameters);
}
