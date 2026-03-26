package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.chatlog.ChatLogManager;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ChatLogManager chatLogManager;
    private final Scheduler streamingScheduler;

    public ChatImpl(JdbcChatMemoryRepository chatMemoryRepository,
                    ParametersRepository parametersRepository,
                    @Qualifier("streamingScheduler") Scheduler streamingScheduler,
                    ToolsManager toolsManager,
                    ChatLogManager chatLogManager) {
        this.parametersRepository = parametersRepository;
        this.streamingScheduler = streamingScheduler;
        this.chatLogManager = chatLogManager;

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
            public void onToolCallStart(String tool, String query) {
                String msg = "Using %s: %s".formatted(tool, query);
                if (externalLogger != null) externalLogger.accept(msg);
                addLogMessage(log, logMessages, msg);
            }

            @Override
            public void onToolRetrieved(String tool, List<StreamEvent.DocScore> documents, long durationMs) {
                String msg = "Retrieved %d docs in %d ms".formatted(documents.size(), durationMs);
                if (externalLogger != null) externalLogger.accept(msg);
                addLogMessage(log, logMessages, msg);
            }

            @Override
            public void onToolReranked(String tool, List<StreamEvent.DocScore> documents, long durationMs) {
                String msg = "Reranked to %d docs in %d ms".formatted(documents.size(), durationMs);
                if (externalLogger != null) externalLogger.accept(msg);
                addLogMessage(log, logMessages, msg);
            }

            @Override
            public void onToolCallEnd(String tool, long totalDurationMs) {
                String msg = "%s done in %d ms".formatted(tool, totalDurationMs);
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
     * Streams the assistant response as a sequence of typed {@link StreamEvent}s via SSE.
     *
     * <p>Events always arrive in this order:
     * <pre>
     * RequestInfo
     *   → [ToolCallStart → ToolRetrieved → ToolReranked → ToolCallEnd]*
     *   → TokensStart → Content* → TokensEnd
     *   → [SourcesStart → Metadata*]
     *   → RequestEnd
     * </pre>
     *
     * <p><b>Why Reactor here:</b> Spring AI executes tools synchronously (blocking),
     * but streams content tokens from OpenAI as a reactive {@code Flux}. We need to merge
     * both into a single output stream — that's why we use {@code Sinks.Many} as a bridge
     * between the blocking callbacks ({@link ToolEventListener}) and the reactive pipeline.
     *
     * <p><b>Key operators explained:</b>
     * <ul>
     *   <li>{@code Flux.defer} — delays execution until someone subscribes. Without it,
     *       the blocking {@code prepareRequest} would run immediately on the caller's thread.</li>
     *   <li>{@code mergeWith} — interleaves two streams as events arrive. Tool events
     *       fire during the blocking phase, content tokens flow after.</li>
     *   <li>{@code concatWith} — plays streams one after another in strict order.</li>
     *   <li>{@code subscribeOn} — moves the whole chain to a dedicated thread pool,
     *       so Tomcat servlet threads are not blocked by JDBC/tool execution.</li>
     * </ul>
     *
     * <p>Console logging and ChatLog persistence are applied transparently
     * by {@link #withDiagnostics} — callers see a clean event stream.
     */
    @Override
    public Flux<StreamEvent> requestStream(String userPrompt,
                                           String parametersYaml,
                                           @Nullable String conversationId) {
        // Event buffer: tool execution is synchronous (blocking), but we need to
        // deliver tool events into a reactive stream. This sink acts as a queue —
        // the listener pushes events in, the flux reads them out.
        Sinks.Many<StreamEvent> toolCallSink = Sinks.many().unicast().onBackpressureBuffer();

        // OpenAI reports token usage only in the very last streaming chunk.
        // We capture it here during streaming, then read when building RequestEnd.
        long startTime = System.currentTimeMillis();
        AtomicInteger promptTokensRef = new AtomicInteger();
        AtomicInteger completionTokensRef = new AtomicInteger();

        // Flux.defer = "don't run this code now, run it when someone subscribes".
        // This is how we move the blocking DB/tool setup off the caller's thread
        // onto streamingScheduler (applied in withDiagnostics via subscribeOn).
        Flux<StreamEvent> stream = Flux.defer(() -> {
            // -- Blocking setup: loads config from DB, creates OpenAI client, resolves tools --
            ToolEventListener listener = createStreamingListener(toolCallSink);
            ChatRequestContext ctx = prepareRequestWithMdc(
                    userPrompt, parametersYaml, conversationId, listener);
            String cid = ctx.conversationId();

            // Tool events (ToolCallStart, ToolRetrieved, etc.) are pushed into the sink
            // by the listener during Spring AI's synchronous tool execution.
            var toolEvents = toolCallSink.asFlux();

            var requestInfo = emit(new StreamEvent.RequestInfo(
                    cid, ctx.chatModel().getDefaultOptions().toString(), userPrompt));

            // Stream content tokens from OpenAI. Each chunk is a ChatResponse object —
            // we extract the text and capture token usage from the last chunk.
            // IMPORTANT: doOnComplete closes the tool sink. Without this, mergeWith
            // below would wait for more tool events forever and the stream would hang.
            var content = ctx.request().stream().chatResponse()
                    .<StreamEvent>flatMap(chunk -> {
                        captureTokenUsage(chunk, promptTokensRef, completionTokensRef);
                        String text = getContentFromChatResponse(chunk);
                        return (text != null && !text.isEmpty())
                                ? Flux.just(new StreamEvent.Content(text))
                                : Flux.empty();
                    })
                    .doOnComplete(toolCallSink::tryEmitComplete);

            // Flux.defer = "evaluate this later, not now". We need it here because
            // ctx.retrievedDocuments() is empty right now — it gets filled while
            // content streams (tools run first, then tokens flow).
            Flux<StreamEvent> sources = Flux.defer(() -> {
                List<String> urls = extractSourceUrls(ctx.retrievedDocuments());
                if (urls.isEmpty()) return Flux.empty();
                return emit(new StreamEvent.SourcesStart())
                        .concatWith(Flux.fromIterable(urls).map(StreamEvent.Metadata::new));
            });

            // Same idea: token counts and total duration are only known after
            // all content has been streamed, so we defer the construction.
            Flux<StreamEvent> summary = Flux.defer(() -> emit(
                    new StreamEvent.RequestEnd(promptTokensRef.get(), completionTokensRef.get(),
                            System.currentTimeMillis() - startTime)));

            // -- Assembly --
            // concatWith = "after this finishes, play the next one" (strict order)
            // mergeWith  = "play both at the same time, interleave as events arrive"
            //
            // Main sequence plays in order: info → tokens → sources → summary.
            // Tool events merge in alongside — they arrive during content streaming
            // but in practice all fire before the first content token.
            var mainSequence = requestInfo
                    .concatWith(emit(new StreamEvent.TokensStart()))
                    .concatWith(content)
                    .concatWith(emit(new StreamEvent.TokensEnd()))
                    .concatWith(sources)
                    .concatWith(summary);

            return toolEvents
                    .mergeWith(mainSequence);
        });

        return withDiagnostics(stream);
    }

    /** Wraps a single event as a Flux — DSL helper for readable stream assembly. */
    private static Flux<StreamEvent> emit(StreamEvent e) {
        return Flux.just(e);
    }

    /**
     * Captures token usage from an OpenAI streaming chunk.
     * Only the final chunk carries non-zero values (when streamUsage is enabled).
     * Earlier chunks report null or zero — safe to overwrite, last value wins.
     */
    private static void captureTokenUsage(ChatResponse chatResponse,
                                          AtomicInteger promptTokensRef,
                                          AtomicInteger completionTokensRef) {
        if (chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            promptTokensRef.set(usage.getPromptTokens() != null ? usage.getPromptTokens() : 0);
            completionTokensRef.set(usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0);
        }
    }

    /**
     * Adds cross-cutting diagnostics to the event stream:
     * <ul>
     *   <li>{@code doOnNext} — logs each event to console via {@link #logEventToConsole}</li>
     *   <li>{@code doOnComplete} — persists the accumulated events as a ChatLog via {@link #persistChatLog}</li>
     *   <li>{@code subscribeOn} — runs the entire chain on {@code streamingScheduler},
     *       keeping Tomcat servlet threads free</li>
     * </ul>
     * All data for ChatLog (conversationId, sources, tokens, log lines) is extracted
     * from the events themselves — no external mutable state needed.
     */
    private Flux<StreamEvent> withDiagnostics(Flux<StreamEvent> stream) {
        List<StreamEvent> eventLog = new ArrayList<>();
        return stream
                .doOnNext(event -> {
                    eventLog.add(event);
                    logEventToConsole(event);
                })
                .doOnComplete(() -> persistChatLog(eventLog))
                .subscribeOn(streamingScheduler);
    }

    /**
     * Builds a ChatLog entity from accumulated stream events and persists it.
     * Extracts conversationId from RequestInfo, sources from Metadata,
     * token counts and duration from RequestEnd, and formats log lines from all events.
     */
    private void persistChatLog(List<StreamEvent> events) {
        String conversationId = null;
        List<String> logLines = new ArrayList<>();
        List<String> sourceUrls = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        long totalDurationMs = 0;

        for (StreamEvent event : events) {
            switch (event) {
                case StreamEvent.RequestInfo ri -> {
                    conversationId = ri.conversationId();
                    logLines.add("Model: %s, User prompt: %s".formatted(ri.model(), ri.userPrompt()));
                }
                case StreamEvent.ToolCallStart tc ->
                        logLines.add("Using %s: %s".formatted(tc.tool(), tc.query()));
                case StreamEvent.ToolRetrieved tr ->
                        logLines.add("Retrieved %d docs in %d ms".formatted(tr.documents().size(), tr.durationMs()));
                case StreamEvent.ToolReranked tr ->
                        logLines.add("Reranked to %d docs in %d ms".formatted(tr.documents().size(), tr.durationMs()));
                case StreamEvent.ToolCallEnd tc ->
                        logLines.add("%s done in %d ms".formatted(tc.tool(), tc.totalDurationMs()));
                case StreamEvent.Metadata m -> sourceUrls.add(m.source());
                case StreamEvent.RequestEnd re -> {
                    promptTokens = re.promptTokens();
                    completionTokens = re.completionTokens();
                    totalDurationMs = re.totalDurationMs();
                    logLines.add("Received response in %d ms [promptTokens: %d, completionTokens: %d]"
                            .formatted(re.totalDurationMs(), re.promptTokens(), re.completionTokens()));
                }
                default -> {}
            }
        }

        if (conversationId != null) {
            chatLogManager.saveStreamResponse(conversationId, logLines,
                    sourceUrls.isEmpty() ? null : String.join(",", sourceUrls),
                    promptTokens, completionTokens, (int) totalDurationMs);
        }
    }

    /** Logs significant stream events to console. Content tokens and markers are skipped. */
    private void logEventToConsole(StreamEvent event) {
        switch (event) {
            case StreamEvent.RequestInfo ri ->
                    log.info("Model: {}, User prompt: {}", ri.model(), abbreviate(ri.userPrompt(), 200));
            case StreamEvent.ToolCallStart tc ->
                    log.info("Using {}: {}", tc.tool(), tc.query());
            case StreamEvent.ToolRetrieved tr ->
                    log.info("Retrieved {} docs in {} ms", tr.documents().size(), tr.durationMs());
            case StreamEvent.ToolReranked tr ->
                    log.info("Reranked to {} docs in {} ms", tr.documents().size(), tr.durationMs());
            case StreamEvent.ToolCallEnd tc ->
                    log.info("{} done in {} ms", tc.tool(), tc.totalDurationMs());
            case StreamEvent.RequestEnd re ->
                    log.info("Received response in {} ms [promptTokens: {}, completionTokens: {}]",
                            re.totalDurationMs(), re.promptTokens(), re.completionTokens());
            default -> {}
        }
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
            return prepareRequest(userPrompt, parametersYaml, conversationId, listener);
        } finally {
            MDC.remove("cid");
        }
    }

    private List<String> extractSourceUrls(List<Document> documents) {
        return documents.stream()
                .map(doc -> doc.getMetadata().get("url"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .toList();
    }

    private ToolEventListener createStreamingListener(Sinks.Many<StreamEvent> toolCallSink) {
        return new ToolEventListener() {
            @Override
            public void onToolCallStart(String tool, String query) {
                toolCallSink.tryEmitNext(new StreamEvent.ToolCallStart(tool, query));
            }

            @Override
            public void onToolRetrieved(String tool, List<StreamEvent.DocScore> documents, long durationMs) {
                toolCallSink.tryEmitNext(new StreamEvent.ToolRetrieved(tool, documents, durationMs));
            }

            @Override
            public void onToolReranked(String tool, List<StreamEvent.DocScore> documents, long durationMs) {
                toolCallSink.tryEmitNext(new StreamEvent.ToolReranked(tool, documents, durationMs));
            }

            @Override
            public void onToolCallEnd(String tool, long totalDurationMs) {
                toolCallSink.tryEmitNext(new StreamEvent.ToolCallEnd(tool, totalDurationMs));
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
                .streamUsage(true)
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
