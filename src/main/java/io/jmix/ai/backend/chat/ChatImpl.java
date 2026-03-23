package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.retrieval.AbstractRagTool;
import io.jmix.ai.backend.retrieval.ToolEventListener;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static io.jmix.ai.backend.retrieval.Utils.addLogMessage;
import static org.apache.commons.lang3.StringUtils.abbreviate;

@Component
public class ChatImpl implements Chat {

    private static final Logger log = LoggerFactory.getLogger(ChatImpl.class);

    private final ParametersRepository parametersRepository;
    private final ChatMemory chatMemory;
    private final ObservationRegistry observationRegistry;
    private final ToolsManager toolsManager;
    private final Scheduler streamingScheduler;

    public ChatImpl(JdbcChatMemoryRepository chatMemoryRepository,
                    ParametersRepository parametersRepository,
                    @Qualifier("streamingScheduler") Scheduler streamingScheduler,
                    ToolsManager toolsManager) {
        this.parametersRepository = parametersRepository;
        this.streamingScheduler = streamingScheduler;

        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();

        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(getChatObservationHandler());
        this.toolsManager = toolsManager;
    }

    private record ChatRequestContext(
            String conversationId,
            ChatModel chatModel,
            ChatClient.ChatClientRequestSpec request,
            List<Document> retrievedDocuments
    ) {
    }

    private ChatRequestContext prepareRequest(String userPrompt, String parametersYaml,
                                              @Nullable String conversationId,
                                              ToolEventListener listener) {
        String nonNullConversationId = conversationId != null
                ? conversationId : UuidProvider.createUuid().toString();

        ParametersReader parametersReader = parametersRepository.getReader(parametersYaml);
        ChatModel chatModel = buildChatModel(parametersReader);
        ChatClient chatClient = buildClient(chatModel);

        List<Document> retrievedDocuments = new ArrayList<>();
        List<AbstractRagTool> tools = toolsManager.getTools(parametersYaml, retrievedDocuments, listener);

        ChatClient.ChatClientRequestSpec request = chatClient.prompt(
                buildPrompt(userPrompt, parametersReader.getString("systemMessage")));
        request.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, nonNullConversationId));
        request.toolCallbacks(tools.stream().map(AbstractRagTool::getToolCallback).toList());

        return new ChatRequestContext(nonNullConversationId, chatModel, request, retrievedDocuments);
    }

    @Override
    public StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                                @Nullable Consumer<String> externalLogger) {
        long start = System.currentTimeMillis();
        List<String> logMessages = new ArrayList<>();

        ToolEventListener listener = new ToolEventListener() {
            @Override
            public void onToolCall(String toolName, String query, long durationMs) {
                String msg = "%s: %s (%d ms)".formatted(toolName, query, durationMs);
                if (externalLogger != null) externalLogger.accept(msg);
                addLogMessage(log, logMessages, msg);
            }

            @Override
            public void onLog(String message) {
                if (externalLogger != null) externalLogger.accept(message);
                addLogMessage(log, logMessages, message);
            }
        };

        ChatRequestContext ctx = prepareRequest(userPrompt, parametersYaml, conversationId, listener);
        MDC.put("cid", ctx.conversationId());
        try {
            addLogMessage(log, logMessages, "Model: %s, User prompt: %s".formatted(
                    ctx.chatModel().getDefaultOptions(), abbreviate(userPrompt, 200)));

            ChatResponse chatResponse = ctx.request().call().chatResponse();

            if (chatResponse == null) {
                addLogMessage(log, logMessages, "No response received from the chat model");
                return new StructuredResponse("", logMessages, ctx.retrievedDocuments(), 0, 0, 0);
            }
            String responseText = getContentFromChatResponse(chatResponse);
            Integer promptTokens = chatResponse.getMetadata().getUsage().getPromptTokens();
            Integer completionTokens = chatResponse.getMetadata().getUsage().getCompletionTokens();

            long responseTime = System.currentTimeMillis() - start;
            addLogMessage(log, logMessages, "Received response in %d ms [promptTokens: %d, completionTokens: %d]:\n%s".formatted(
                    responseTime, promptTokens, completionTokens, abbreviate(responseText, 100)));

            return new StructuredResponse(responseText, logMessages, ctx.retrievedDocuments(),
                    promptTokens, completionTokens, (int) responseTime);
        } finally {
            MDC.remove("cid");
        }
    }

    /**
     * Streams the assistant response as a sequence of {@link StreamEvent}s via SSE.
     *
     * <p>Events always arrive in this order:
     * <pre>
     * ToolCall* → TokensStart → Content* → TokensEnd → [SourcesStart → Metadata*]
     * </pre>
     *
     * <p><b>Why Reactor here:</b> Spring AI executes tool calls synchronously (blocking),
     * but streams content tokens from OpenAI as a reactive {@code Flux}. We need to merge
     * both into a single output stream — that's why we use {@code Sinks.Many} as a bridge
     * between the blocking callback ({@link ToolEventListener}) and the reactive pipeline.
     *
     * <p><b>Key operators explained:</b>
     * <ul>
     *   <li>{@code Flux.defer} — delays execution until someone subscribes. Without it,
     *       the blocking {@code prepareRequest} would run immediately on the caller's thread.</li>
     *   <li>{@code mergeWith} — interleaves two streams as events arrive. Tool call events
     *       fire during the blocking setup phase, content tokens flow after.</li>
     *   <li>{@code Flux.concat} — plays streams one after another in strict order:
     *       TokensStart → content tokens → TokensEnd → sources.</li>
     *   <li>{@code subscribeOn} — moves the whole chain to a dedicated thread pool,
     *       so Tomcat servlet threads are not blocked by JDBC/tool execution.</li>
     * </ul>
     */
    @Override
    public Flux<StreamEvent> requestStream(String userPrompt, String parametersYaml, @Nullable String conversationId) {
        // Think of this as a queue: our ToolEventListener pushes events into it,
        // and the Flux reads from the other end. Buffered, so events are not lost
        // even if nobody is reading yet.
        Sinks.Many<StreamEvent> toolCallSink = Sinks.many().unicast().onBackpressureBuffer();

        return Flux.defer(() -> {
                    // Blocking: loads parameters from DB, builds the model client, resolves tools.
                    // Runs on streamingScheduler thread (not Tomcat).
                    ChatRequestContext ctx = prepareRequestWithMdc(
                            userPrompt, parametersYaml, conversationId, createStreamingListener(toolCallSink));

                    // Tool call events arrive via the sink (pushed by ToolEventListener).
                    Flux<StreamEvent> toolCallsFlux = toolCallSink.asFlux();

                    // Content tokens from OpenAI. Each string chunk becomes a Content event.
                    // When the last token arrives, we close the tool call sink —
                    // otherwise mergeWith would hang waiting for more tool calls forever.
                    Flux<StreamEvent> contentFlux = ctx.request()
                            .stream()
                            .content()
                            .<StreamEvent>map(StreamEvent.Content::new)
                            .doOnComplete(toolCallSink::tryEmitComplete);

                    // Source URLs from documents that tools found during execution.
                    // Wrapped in Flux.defer because the document list is empty at assembly time —
                    // it gets populated later, while content tokens are streaming.
                    // SourcesStart is only emitted when there are actual sources.
                    Flux<StreamEvent> sourcesFlux = Flux.defer(() -> {
                        List<String> urls = extractSourceUrls(ctx.retrievedDocuments());
                        if (urls.isEmpty()) return Flux.empty();
                        return Flux.concat(
                                Flux.just(new StreamEvent.SourcesStart()),
                                Flux.fromIterable(urls).map(StreamEvent.Metadata::new));
                    });

                    // Final assembly:
                    // - mergeWith: tool calls interleave with the main sequence as they arrive
                    // - Flux.concat: TokensStart → content → TokensEnd → sources (strict order)
                    return toolCallsFlux
                            .mergeWith(Flux.concat(
                                    Flux.just(new StreamEvent.TokensStart()),
                                    contentFlux,
                                    Flux.just(new StreamEvent.TokensEnd()),
                                    sourcesFlux));
                })
                .subscribeOn(streamingScheduler);
    }

    /**
     * Blocking setup with MDC "cid" for logging.
     * MDC is thread-local, so we clean it up before returning — subsequent reactive
     * operators may run on different threads.
     */
    private ChatRequestContext prepareRequestWithMdc(String userPrompt, String parametersYaml,
                                                     @Nullable String conversationId,
                                                     ToolEventListener listener) {
        try {
            MDC.put("cid", conversationId != null ? conversationId : "");
            ChatRequestContext ctx = prepareRequest(userPrompt, parametersYaml, conversationId, listener);
            log.info("Streaming: model={}, prompt={}",
                    ctx.chatModel().getDefaultOptions(), abbreviate(userPrompt, 200));
            return ctx;
        } finally {
            MDC.remove("cid");
        }
    }

    private List<String> extractSourceUrls(List<Document> documents) {
        List<String> urls = documents.stream()
                .map(doc -> doc.getMetadata().get("url"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .toList();
        return urls;
    }

    private ToolEventListener createStreamingListener(Sinks.Many<StreamEvent> toolCallSink) {
        return new ToolEventListener() {
            @Override
            public void onToolCall(String toolName, String query, long durationMs) {
                toolCallSink.tryEmitNext(new StreamEvent.ToolCall(toolName, query, durationMs));
            }

            @Override
            public void onLog(String message) {
                log.info(message);
            }
        };
    }

    @Nullable
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
