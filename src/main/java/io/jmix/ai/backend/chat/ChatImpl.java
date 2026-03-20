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
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.jmix.ai.backend.retrieval.Utils.addLogMessage;
import static io.jmix.ai.backend.retrieval.Utils.getDistinctDocuments;
import static org.apache.commons.lang3.StringUtils.abbreviate;

@Component
public class ChatImpl implements Chat {

    private static final Logger log = LoggerFactory.getLogger(ChatImpl.class);
    private static final ChatQueryClassifier QUERY_CLASSIFIER = new ChatQueryClassifier();
    private static final Pattern EXACT_IDENTIFIER_PATTERN = Pattern.compile(
            "(?iu)(" +
                    "@[a-z_][a-z0-9_]*|" +
                    "\\b(beforeActionPerformedHandler|afterSaveHandler|loadDelegate|saveDelegate|removeDelegate|totalCountDelegate|optionCaptionProvider|valueProvider)\\b|" +
                    "\\b[a-z][a-z0-9]*?(delegate|provider|handler|listener|formatter|validator)\\b" +
                    ")"
    );

    private final ParametersRepository parametersRepository;
    private final ChatMemory chatMemory;
    private final ObservationRegistry observationRegistry;
    private final ToolsManager toolsManager;
    private final String openAiBaseUrl;
    private final String openAiApiKeyProperty;

    public ChatImpl(JdbcChatMemoryRepository chatMemoryRepository,
                    ParametersRepository parametersRepository, ToolsManager toolsManager, Environment environment) {
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
        return requestStructuredStreaming(userPrompt, parametersYaml, conversationId, null, null, externalLogger);
    }

    @Override
    public StructuredResponse requestStructuredStreaming(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                                         @Nullable BooleanSupplier cancellationRequested,
                                                         @Nullable Consumer<String> chunkConsumer,
                                                         @Nullable Consumer<String> externalLogger) {
        long start = System.currentTimeMillis();
        List<String> logMessages = new ArrayList<>();
        String nonNullConversationId = conversationId != null ? conversationId : UuidProvider.createUuid().toString();
        MDC.put("cid", nonNullConversationId);
        try {
            long phaseStart = start;
            ParametersReader parametersReader = parametersRepository.getReader(parametersYaml);
            long parametersReaderMs = elapsed(phaseStart);

            phaseStart = System.currentTimeMillis();
            ChatModel chatModel = buildChatModel(parametersReader);
            long chatModelMs = elapsed(phaseStart);
            addLogMessage(log, logMessages, "Model: %s, User prompt: %s".formatted(chatModel.getDefaultOptions(), abbreviate(userPrompt, 200)));

            phaseStart = System.currentTimeMillis();
            ChatClient chatClient = buildClient(chatModel);
            long chatClientMs = elapsed(phaseStart);

            List<Document> retrievedDocuments = new ArrayList<>();

            Consumer<String> internalLogger = message -> {
                if (externalLogger != null)
                    externalLogger.accept(message);
                addLogMessage(log, logMessages, message);
            };

            ChatClient.ChatClientRequestSpec request;

            phaseStart = System.currentTimeMillis();
            boolean enableTools = shouldEnableTools(userPrompt, parametersReader);
            List<AbstractRagTool> tools = enableTools
                    ? toolsManager.getTools(parametersYaml, retrievedDocuments, internalLogger)
                    : List.of();
            long toolsMs = elapsed(phaseStart);

            if (enableTools) {
                addLogMessage(log, logMessages, "Tool callbacks enabled: %d".formatted(tools.size()));
            } else {
                addLogMessage(log, logMessages, "Tool callbacks skipped for non-technical prompt");
            }

            String prefetchedContext = null;
            if (enableTools) {
                prefetchedContext = prefetchPrimaryRetrievalContext(userPrompt, tools, internalLogger);
            }

            phaseStart = System.currentTimeMillis();
            request = chatClient.prompt(buildPrompt(userPrompt, parametersReader.getString("systemMessage"), prefetchedContext));
            request.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, nonNullConversationId));
            if (!tools.isEmpty()) {
                request.toolCallbacks(tools.stream().map(AbstractRagTool::getToolCallback).toList());
            }
            long requestPreparationMs = elapsed(phaseStart);

            phaseStart = System.currentTimeMillis();
            StringBuilder responseTextBuilder = new StringBuilder();
            List<ChatResponse> chatResponses = new ArrayList<>();
            final int[] promptTokensHolder = {0};
            final int[] completionTokensHolder = {0};
            AtomicLong firstTokenMs = new AtomicLong(-1);
            Flux<ChatResponse> responseFlux = request.stream().chatResponse();
            CountDownLatch completionLatch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Disposable subscription = responseFlux.subscribe(chatResponse -> {
                        chatResponses.add(chatResponse);
                        Optional.ofNullable(chatResponse.getMetadata())
                                .map(metadata -> metadata.getUsage())
                                .ifPresent(usage -> {
                                    if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                                        promptTokensHolder[0] = Math.max(promptTokensHolder[0], usage.getPromptTokens());
                                    }
                                    if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) {
                                        completionTokensHolder[0] = Math.max(completionTokensHolder[0], usage.getCompletionTokens());
                                    }
                                });
                        String chunkText = getContentFromChatResponse(chatResponse);
                        if (StringUtils.isNotBlank(chunkText)) {
                            if (firstTokenMs.get() < 0) {
                                firstTokenMs.set(System.currentTimeMillis() - start);
                            }
                            responseTextBuilder.append(chunkText);
                            if (chunkConsumer != null) {
                                chunkConsumer.accept(chunkText);
                            }
                        }
                    },
                    ex -> {
                        errorRef.set(ex);
                        completionLatch.countDown();
                    },
                    completionLatch::countDown);

            try {
                while (completionLatch.getCount() > 0) {
                    if (isCancellationRequested(cancellationRequested)) {
                        cancelled.set(true);
                        addLogMessage(log, logMessages, "Streaming request cancelled");
                        subscription.dispose();
                        completionLatch.countDown();
                        break;
                    }
                    completionLatch.await(100, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                cancelled.set(true);
                addLogMessage(log, logMessages, "Streaming request interrupted");
                subscription.dispose();
                Thread.currentThread().interrupt();
            }

            if (!cancelled.get() && errorRef.get() != null) {
                if (errorRef.get() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(errorRef.get());
            }
            long llmCallMs = elapsed(phaseStart);

            phaseStart = System.currentTimeMillis();
            List<Document> distinctDocuments = getDistinctDocuments(retrievedDocuments);
            long postProcessingMs = elapsed(phaseStart);

            ChatResponse chatResponse = chatResponses.isEmpty() ? null : chatResponses.get(chatResponses.size() - 1);

            if (cancelled.get()) {
                long responseTime = System.currentTimeMillis() - start;
                addLogMessage(log, logMessages,
                        "Timing breakdown [ms]: parameters=%d, model=%d, client=%d, tools=%d, request=%d, firstToken=%d, llm=%d, post=%d, total=%d".formatted(
                                parametersReaderMs, chatModelMs, chatClientMs, toolsMs, requestPreparationMs, firstTokenMs.get(), llmCallMs, postProcessingMs, responseTime));
                return new StructuredResponse(responseTextBuilder.toString(), logMessages, distinctDocuments,
                        promptTokensHolder[0], completionTokensHolder[0], (int) responseTime);
            }

            if (chatResponse == null) {
                addLogMessage(log, logMessages, "No response received from the chat model");
                return new StructuredResponse("", logMessages, distinctDocuments, 0, 0, 0);
            }
            String responseText = responseTextBuilder.toString();
            Integer promptTokens = Optional.ofNullable(chatResponse.getMetadata())
                    .map(metadata -> metadata.getUsage())
                    .map(usage -> usage.getPromptTokens())
                    .filter(tokens -> tokens > 0)
                    .orElse(promptTokensHolder[0]);
            Integer completionTokens = Optional.ofNullable(chatResponse.getMetadata())
                    .map(metadata -> metadata.getUsage())
                    .map(usage -> usage.getCompletionTokens())
                    .filter(tokens -> tokens > 0)
                    .orElse(completionTokensHolder[0]);

            long responseTime = System.currentTimeMillis() - start;
            addLogMessage(log, logMessages,
                    "Timing breakdown [ms]: parameters=%d, model=%d, client=%d, tools=%d, request=%d, firstToken=%d, llm=%d, post=%d, total=%d".formatted(
                            parametersReaderMs, chatModelMs, chatClientMs, toolsMs, requestPreparationMs, firstTokenMs.get(), llmCallMs, postProcessingMs, responseTime));
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

    private static Prompt buildPrompt(String userPrompt, String systemPrompt, @Nullable String prefetchedContext) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (StringUtils.isNotBlank(prefetchedContext)) {
            messages.add(new SystemMessage("""
                    Retrieved retrieval context:
                    %s
                    """.formatted(prefetchedContext)));
        }
        messages.add(new UserMessage(userPrompt));
        return new Prompt(messages);
    }

    @Nullable
    private String prefetchPrimaryRetrievalContext(String userPrompt, List<AbstractRagTool> tools, Consumer<String> internalLogger) {
        ChatQueryClassifier.RetrievalPlan retrievalPlan = QUERY_CLASSIFIER.buildRetrievalPlan(userPrompt);
        internalLogger.accept("Retrieval plan: %s".formatted(retrievalPlan.toolNames()));

        List<String> exactIdentifiers = extractExactIdentifiers(userPrompt);
        List<String> contextSections = new ArrayList<>();
        for (String toolName : retrievalPlan.toolNames()) {
            String context = executePrefetchTool(toolName, userPrompt, tools, internalLogger);
            if (StringUtils.isBlank(context)) {
                continue;
            }
            if (!isContextRelevantToExactIdentifiers(context, exactIdentifiers)) {
                internalLogger.accept("Skipping prefetched context [%s]: no exact identifier match for %s"
                        .formatted(toolName, exactIdentifiers));
                continue;
            }
            contextSections.add("""
                    %s:
                    %s
                    """.formatted(getContextSectionTitle(toolName), context));
        }

        if (contextSections.isEmpty()) {
            return null;
        }
        String combinedContext = String.join("\n\n", contextSections);
        internalLogger.accept("Prefetched context size [chars]: %d".formatted(combinedContext.length()));
        return combinedContext;
    }

    @Nullable
    private String executePrefetchTool(String toolName, String userPrompt, List<AbstractRagTool> tools, Consumer<String> internalLogger) {
        for (AbstractRagTool tool : tools) {
            if (toolName.equals(tool.getToolName())) {
                internalLogger.accept("Prefetching %s before model call".formatted(toolName));
                long startedAt = System.currentTimeMillis();
                String context = tool.execute(userPrompt);
                long durationMs = System.currentTimeMillis() - startedAt;
                int chars = context != null ? context.length() : 0;
                internalLogger.accept("Prefetch result [%s]: %d ms, %d chars".formatted(toolName, durationMs, chars));
                return context;
            }
        }
        return null;
    }

    private static List<String> extractExactIdentifiers(String userPrompt) {
        if (StringUtils.isBlank(userPrompt)) {
            return List.of();
        }

        Matcher matcher = EXACT_IDENTIFIER_PATTERN.matcher(userPrompt);
        Set<String> identifiers = new LinkedHashSet<>();
        while (matcher.find()) {
            String raw = matcher.group();
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            identifiers.add(raw);
            if (raw.startsWith("@") && raw.length() > 1) {
                identifiers.add(raw.substring(1));
            }
        }
        return List.copyOf(identifiers);
    }

    private static boolean isContextRelevantToExactIdentifiers(String context, List<String> exactIdentifiers) {
        if (exactIdentifiers.isEmpty() || StringUtils.isBlank(context)) {
            return true;
        }

        String lowerContext = context.toLowerCase();
        for (String identifier : exactIdentifiers) {
            if (lowerContext.contains(identifier.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private ChatModel buildChatModel(ParametersReader parametersReader) {
        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        if (StringUtils.isBlank(openaiApiKey)) {
            openaiApiKey = openAiApiKeyProperty;
        }
        if (StringUtils.isBlank(openaiApiKey)) {
            if (StringUtils.isBlank(openAiBaseUrl)) {
                throw new IllegalStateException("OPENAI API key is not set (spring.ai.openai.api-key or OPENAI_API_KEY)");
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

    static boolean shouldEnableTools(String userPrompt, ParametersReader parametersReader) {
        if (!parametersReader.getBoolean("tools.skipForTrivialPrompts", true)) {
            return true;
        }
        return QUERY_CLASSIFIER.isTechnicalPrompt(userPrompt);
    }

    private static long elapsed(long phaseStart) {
        return System.currentTimeMillis() - phaseStart;
    }

    private static boolean isCancellationRequested(@Nullable BooleanSupplier cancellationRequested) {
        return cancellationRequested != null && cancellationRequested.getAsBoolean();
    }

    static ChatQueryClassifier.RetrievalPlan buildRetrievalPlan(@Nullable String userPrompt) {
        return QUERY_CLASSIFIER.buildRetrievalPlan(userPrompt);
    }

    static boolean isUiPrompt(@Nullable String userPrompt) {
        return QUERY_CLASSIFIER.isUiPrompt(userPrompt);
    }

    static boolean isFrameworkPrompt(@Nullable String userPrompt) {
        return QUERY_CLASSIFIER.isFrameworkPrompt(userPrompt);
    }

    private static String getContextSectionTitle(String toolName) {
        return switch (toolName) {
            case "documentation_retriever" -> "Documentation context";
            case "framework_retriever" -> "Framework source context";
            case "uisamples_retriever" -> "UI samples context";
            case "trainings_retriever" -> "Trainings context";
            default -> "Retrieved context";
        };
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
