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
            public void onToolCall(String toolName, String query) {
                String msg = "Using " + toolName + ": " + query;
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
     * Streams the assistant response as a flat sequence of {@link StreamEvent}s.
     *
     * <p>The stream has three phases, always in this order:
     * <ol>
     *   <li><b>Tool calls</b> — "Searching documentation...", "Searching forum..." etc.</li>
     *   <li><b>Content</b> — response text, token by token</li>
     *   <li><b>Sources</b> — URLs of documents used to generate the response</li>
     * </ol>
     *
     * <h4>How it works (Reactor reactive streams)</h4>
     *
     * <p>The challenge: tool call events happen <b>synchronously</b> inside Spring AI
     * (when the model decides to invoke a RAG tool), but content tokens arrive as a
     * <b>reactive stream</b> from OpenAI SSE. We need to combine both into one output.
     *
     * <p>Solution: we use a {@link Sinks.Many} as a bridge. Our {@link ToolEventListener}
     * pushes ToolCall events into the sink during tool execution. Then we combine three Flux sources:
     * <pre>
     * toolCallsFlux.mergeWith(contentFlux).concatWith(sourcesFlux)
     * </pre>
     * <ul>
     *   <li>{@code mergeWith} — tool calls and content arrive concurrently (tool calls fire
     *       during content setup, before tokens start flowing). Merge interleaves both.</li>
     *   <li>{@code concatWith} — sources are appended strictly after all content tokens.
     *       They're extracted from documents that tools retrieved during Phase 1.</li>
     * </ul>
     *
     * <p>When content stream completes, we close the toolCallSink via {@code doOnComplete},
     * which signals mergeWith to finish. Then concatWith kicks in and emits sources.
     *
     * <p>All blocking work (JDBC, tool resolution) runs on a dedicated
     * {@code streamingScheduler} thread pool via {@code subscribeOn}, keeping
     * Tomcat servlet threads free for other requests.
     */
    @Override
    public Flux<StreamEvent> requestStream(String userPrompt, String parametersYaml, @Nullable String conversationId) {
        // Sink that collects tool call events as they happen during model processing.
        // Spring AI calls our ToolEventListener synchronously when a tool is invoked —
        // the listener pushes ToolCall events into this sink.
        Sinks.Many<StreamEvent> toolCallSink = Sinks.many().unicast().onBackpressureBuffer();

        return Flux.defer(() -> {
                    // Blocking setup on streamingScheduler thread.
                    // MDC "cid" is set for the duration so tool/search logs include conversation id.
                    ChatRequestContext ctx = prepareRequestWithMdc(
                            userPrompt, parametersYaml, conversationId, createStreamingListener(toolCallSink));

                    // Phase 1: Tool call events (e.g. "Searching docs for: jmix security")
                    // These arrive via toolCallSink while Spring AI processes tool calls.
                    Flux<StreamEvent> toolCallsFlux = toolCallSink.asFlux();

                    // Phase 2: Content tokens from OpenAI (the actual response text).
                    // When content finishes, close the tool call sink.
                    // Without this, mergeWith would wait for toolCallSink forever → deadlock.
                    Flux<StreamEvent> contentFlux = ctx.request()
                            .stream()
                            .content()
                            .<StreamEvent>map(StreamEvent.Content::new)
                            .doOnComplete(toolCallSink::tryEmitComplete);

                    // Phase 3: Source URLs extracted from documents that tools retrieved
                    Flux<StreamEvent> sourcesFlux = Flux.fromIterable(extractSourceUrls(ctx.retrievedDocuments()))
                            .map(List::of)
                            .map(StreamEvent.Metadata::new);

                    // Combine: tool calls interleave with content (mergeWith),
                    // sources come strictly after all content (concatWith).
                    return toolCallsFlux
                            .mergeWith(contentFlux)
                            .concatWith(sourcesFlux);
                })
                // Run on a dedicated thread pool, not on Tomcat servlet threads
                .subscribeOn(streamingScheduler);
    }

    /**
     * Runs blocking setup with MDC "cid" set for the duration.
     * Tool execution, vector search, reranking — all log entries will include conversation id.
     * MDC is cleaned up after setup because subsequent reactive operators may run on other threads.
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
            public void onToolCall(String toolName, String query) {
                toolCallSink.tryEmitNext(new StreamEvent.ToolCall(toolName, query));
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
