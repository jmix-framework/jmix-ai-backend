package io.jmix.ai.backend.vectorstore;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Shared OpenAI plumbing for the LLM enrichers: API-key resolution, client construction with
 * structured (JSON-schema) output, the model cache key, and response-text extraction.
 */
public abstract class AbstractOpenAiEnricher {

    private final String modelName;
    private final String reasoningEffort;
    private final OpenAiApi openAiApi;
    private ChatModel chatModel;

    protected AbstractOpenAiEnricher(
            String modelName,
            String reasoningEffort,
            String configuredApiKey,
            boolean requireApiKey,
            Duration connectTimeout,
            Duration readTimeout) {
        this.modelName = modelName;
        this.reasoningEffort = reasoningEffort;
        String apiKey = StringUtils.defaultIfBlank(configuredApiKey, System.getenv("OPENAI_API_KEY"));
        if (requireApiKey && StringUtils.isBlank(apiKey)) {
            throw new IllegalStateException("OPENAI API key is not set (spring.ai.openai.api-key or OPENAI_API_KEY)");
        }
        if (StringUtils.isBlank(apiKey)) {
            this.openAiApi = null;
        } else {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            this.openAiApi = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                    .build();
        }
    }

    /** The structured-output schema the model must produce. */
    protected abstract BeanOutputConverter<?> outputConverter();

    /** Cache key for generated output: model plus reasoning effort, since both affect the result. */
    public String getModelKey() {
        return StringUtils.isBlank(reasoningEffort) ? modelName : modelName + ":" + reasoningEffort;
    }

    protected final synchronized ChatModel chatModel() {
        if (openAiApi == null) {
            throw new IllegalStateException("OPENAI API key is not set (spring.ai.openai.api-key or OPENAI_API_KEY)");
        }
        if (chatModel == null) {
            chatModel = createChatModel();
        }
        return chatModel;
    }

    /** Creates the immutable, thread-safe model lazily; one instance is reused by this enricher. */
    protected ChatModel createChatModel() {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(modelName)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, outputConverter().getJsonSchema()));
        if (!StringUtils.isBlank(reasoningEffort)) {
            options.reasoningEffort(reasoningEffort);
        }
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
                .build();
    }

    @Nullable
    protected static String getContent(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }
}
