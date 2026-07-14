package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;

@FunctionalInterface
public interface ChatModelFactory {

    ChatModel build(ParametersReader parametersReader, ObservationRegistry observationRegistry);
}
