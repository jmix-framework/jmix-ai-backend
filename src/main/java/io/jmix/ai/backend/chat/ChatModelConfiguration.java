package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import io.micrometer.observation.ObservationRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatModelConfiguration {

    @Bean
    public ChatModelFactory chatModelFactory() {
        return ChatModelConfiguration::buildChatModel;
    }

    private static ChatModel buildChatModel(ParametersReader parametersReader,
                                            ObservationRegistry observationRegistry) {
        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        if (StringUtils.isBlank(openaiApiKey)) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is not set");
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(openaiApiKey)
                .build();

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(parametersReader.getString("model.name", "gpt-5"));

        Double temperature = parametersReader.getDouble("model.temperature", null);
        if (temperature != null)
            optionsBuilder.temperature(temperature);

        String reasoningEffort = parametersReader.getString("model.reasoningEffort", null);
        if (reasoningEffort != null)
            optionsBuilder.reasoningEffort(reasoningEffort);

        OpenAiChatOptions openAiChatOptions = optionsBuilder
                .streamUsage(true)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(openAiChatOptions)
                .observationRegistry(observationRegistry)
                .build();
    }
}
