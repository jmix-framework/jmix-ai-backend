package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.retrieval.AbstractRagTool;
import io.jmix.ai.backend.retrieval.ToolsManager;
import io.jmix.core.UuidProvider;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static io.jmix.ai.backend.retrieval.Utils.addLogMessage;
import static io.jmix.ai.backend.retrieval.Utils.getDistinctDocuments;
import static org.apache.commons.lang3.StringUtils.abbreviate;

@Component
public class ChatImpl implements Chat {

    private static final Logger log = LoggerFactory.getLogger(ChatImpl.class);

    private final ParametersRepository parametersRepository;
    private final ChatMemory chatMemory;
    private final ObservationRegistry observationRegistry;
    private final ToolsManager toolsManager;
    private final String openAiBaseUrl;
    private final String openAiApiKeyProperty;

    public ChatImpl(JdbcChatMemoryRepository chatMemoryRepository,
                    ParametersRepository parametersRepository, ToolsManager toolsManager,
                    Environment environment) {
        this.parametersRepository = parametersRepository;

        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();

        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(getChatObservationHandler());
        this.toolsManager = toolsManager;
        this.openAiBaseUrl = environment.getProperty("spring.ai.openai.base-url");
        this.openAiApiKeyProperty = environment.getProperty("spring.ai.openai.api-key");
    }

    @Override
    public StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                                @Nullable Consumer<String> externalLogger) {
        long start = System.currentTimeMillis();
        List<String> logMessages = new ArrayList<>();
        String nonNullConversationId = conversationId != null ? conversationId : UuidProvider.createUuid().toString();
        MDC.put("cid", nonNullConversationId);
        try {
            ParametersReader parametersReader = parametersRepository.getReader(parametersYaml);

            ChatModel chatModel = buildChatModel(parametersReader);
            addLogMessage(log, logMessages, "Model: %s, User prompt: %s".formatted(chatModel.getDefaultOptions(), abbreviate(userPrompt, 200)));

            ChatClient chatClient = buildClient(chatModel);

            List<Document> retrievedDocuments = new ArrayList<>();

            Consumer<String> internalLogger = message -> {
                if (externalLogger != null)
                    externalLogger.accept(message);
                addLogMessage(log, logMessages, message);
            };

            ChatClient.ChatClientRequestSpec request;

            List<AbstractRagTool> tools = toolsManager.getTools(parametersYaml, retrievedDocuments, internalLogger);

            request = chatClient.prompt(buildPrompt(userPrompt, parametersReader.getString("systemMessage")));
            request.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, nonNullConversationId));
            request.toolCallbacks(tools.stream().map(AbstractRagTool::getToolCallback).toList());

            ChatResponse chatResponse = request.call().chatResponse();

            List<Document> distinctDocuments = getDistinctDocuments(retrievedDocuments);

            if (chatResponse == null) {
                addLogMessage(log, logMessages, "No response received from the chat model");
                return new StructuredResponse("", logMessages, distinctDocuments, 0, 0, 0);
            }
            String responseText = getContentFromChatResponse(chatResponse);
            Integer promptTokens = chatResponse.getMetadata().getUsage().getPromptTokens();
            Integer completionTokens = chatResponse.getMetadata().getUsage().getCompletionTokens();

            long responseTime = System.currentTimeMillis() - start;
            addLogMessage(log, logMessages, "Received response in %d ms [promptTokens: %d, completionTokens: %d]:\n%s".formatted(
                    responseTime, promptTokens, completionTokens, abbreviate(responseText, 100)));

            return new StructuredResponse(responseText, logMessages, distinctDocuments,
                    promptTokens, completionTokens, (int) responseTime);
        } finally {
            MDC.remove("cid");
        }
    }

    private static String getContentFromChatResponse(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    private Prompt buildPrompt(String userPrompt, String systemPrompt) {
        return new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));
    }

    private ChatModel buildChatModel(ParametersReader parametersReader) {
        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        if (StringUtils.isBlank(openaiApiKey)) {
            openaiApiKey = openAiApiKeyProperty;
        }
        if (StringUtils.isBlank(openaiApiKey)) {
            if (StringUtils.isBlank(openAiBaseUrl)) {
                throw new IllegalStateException("OPENAI_API_KEY environment variable is not set");
            }
            openaiApiKey = "dummy";
        }
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .apiKey(openaiApiKey);
        if (StringUtils.isNotBlank(openAiBaseUrl)) {
            apiBuilder.baseUrl(openAiBaseUrl);
        }
        OpenAiApi openAiApi = apiBuilder.build();

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(parametersReader.getString("model.name", "gpt-5"));

        Double temperature = parametersReader.getDouble("model.temperature", null);
        if (temperature != null)
            optionsBuilder.temperature(temperature);

        String reasoningEffort = parametersReader.getString("model.reasoningEffort", null);
        if (reasoningEffort != null)
            optionsBuilder.reasoningEffort(reasoningEffort);

        OpenAiChatOptions openAiChatOptions = optionsBuilder
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(openAiChatOptions)
                .observationRegistry(observationRegistry)
                .build();
    }

    private ChatClient buildClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    private ObservationHandler<ChatModelObservationContext> getChatObservationHandler() {
        return new ObservationHandler<>() {
            @Override
            public void onStart(ChatModelObservationContext context) {
                log.trace("LLM Request:\n{}", context.getRequest());
            }

            @Override
            public void onStop(ChatModelObservationContext context) {
                log.trace("LLM Response:\n{}", context.getResponse());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ChatModelObservationContext;
            }
        };
    }
}
