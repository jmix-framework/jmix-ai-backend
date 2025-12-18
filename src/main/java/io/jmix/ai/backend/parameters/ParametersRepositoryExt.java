package io.jmix.ai.backend.parameters;

import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;

public interface ParametersRepositoryExt {
    Parameters loadActive(ParametersTargetType type);

    Parameters copy(Parameters parameters);

    String loadDefaultContent();

    void activate(Parameters parameters);

    ParametersReader getReader(Parameters parameters);

    ParametersReader getReader(String parametersYaml);
}
