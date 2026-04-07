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
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final ChatLogManager chatLogManager;
    private final Scheduler streamingScheduler;
    private final String openAiBaseUrl;
    private final String openAiApiKeyProperty;

    public ChatImpl(JdbcChatMemoryRepository chatMemoryRepository,
                    ParametersRepository parametersRepository,
                    @Qualifier("streamingScheduler") Scheduler streamingScheduler,
                    ToolsManager toolsManager,
                    ChatLogManager chatLogManager,
                    Environment environment) {
        this.parametersRepository = parametersRepository;
        this.streamingScheduler = streamingScheduler;
        this.chatLogManager = chatLogManager;
        this.openAiBaseUrl = environment.getProperty("spring.ai.openai.base-url");
        this.openAiApiKeyProperty = environment.getProperty("spring.ai.openai.api-key");

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

        boolean enableTools = shouldEnableTools(userPrompt, parametersReader);
        List<AbstractRagTool> tools = enableTools
                ? toolsManager.getTools(parametersYaml, retrievedDocuments, listener)
                : List.of();

        String prefetchedContext = null;
        if (enableTools) {
            prefetchedContext = prefetchPrimaryRetrievalContext(userPrompt, tools, msg -> listener.onLog(msg));
        }

        ChatClient.ChatClientRequestSpec request = chatClient.prompt(
                buildPrompt(userPrompt, parametersReader.getString("systemMessage"), prefetchedContext));
        request.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, nonNullConversationId));
        if (!tools.isEmpty()) {
            request.toolCallbacks(tools.stream().map(AbstractRagTool::getToolCallback).toList());
        }

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
                String msg = ">>> Using %s: %s".formatted(tool, query);
                if (externalLogger != null) externalLogger.accept(msg);
                addLogMessage(log, logMessages, msg);
            }

            @Override
            public void onToolRetrieved(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                String msg = "Found documents (%d): %s".formatted(documents.size(), formatDocScores(documents));
                if (externalLogger != null) externalLogger.accept(msg);
                addLogMessage(log, logMessages, msg);
            }

            @Override
            public void onToolReranked(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                String msg = "Reranked documents (%d): %s".formatted(documents.size(), formatDocScores(documents));
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

            ToolEventListener listener = createLoggingListener(internalLogger);

            phaseStart = System.currentTimeMillis();
            boolean enableTools = shouldEnableTools(userPrompt, parametersReader);
            List<AbstractRagTool> tools = enableTools
                    ? toolsManager.getTools(parametersYaml, retrievedDocuments, listener)
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

            ChatClient.ChatClientRequestSpec request;

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

    /**
     * Streams the assistant response as a sequence of typed {@link EventStreamValueHolder}s via SSE.
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
    public Flux<StreamingEvent> requestStream(String userPrompt,
                                                      String parametersYaml,
                                                      @Nullable String conversationId) {
        // Flux.defer = "don't run this code now, run it when someone subscribes".
        // This is how we move the blocking DB/tool setup off the caller's thread
        // onto streamingScheduler (applied in withDiagnostics via subscribeOn).
        //
        // NOTE: returned Flux is single-subscription only (unicast sink).
        // Do not cache, share, or resubscribe — each call to requestStream
        // must create a fresh subscription.
        Flux<EventStreamValueHolder> stream = Flux.defer(() -> {
            // Event buffer: tool execution is synchronous (blocking), but we need to
            // deliver tool events into a reactive stream. This sink acts as a queue —
            // the listener pushes events in, the flux reads them out.
            Sinks.Many<EventStreamValueHolder> toolCallSink = Sinks.many().unicast().onBackpressureBuffer();
            long startTime = System.currentTimeMillis();
            AtomicInteger promptTokensRef = new AtomicInteger();
            AtomicInteger completionTokensRef = new AtomicInteger();
            // -- Blocking setup: loads config from DB, creates OpenAI client, resolves tools --
            String cid = conversationId != null ? conversationId : "";

            // Pushes tool lifecycle events into the sink.
            // MDC "cid" is set from onToolCallStart to onToolCallEnd
            // so any logging during tool execution (e.g. Reranker) includes conversation id.
            ToolEventListener listener = createStreamingListener(toolCallSink, cid);
            ChatRequestContext ctx = prepareRequest(
                    userPrompt, parametersYaml, conversationId, listener);

            // Tool events (ToolCallStart, ToolRetrieved, etc.) are pushed into the sink
            // by the listener during Spring AI's synchronous tool execution.
            var toolEvents = toolCallSink.asFlux();

            // Request metadata — model config and user prompt
            var requestInfo = emit(new EventStreamValueHolder.RequestInfo(
                    ctx.chatModel().getDefaultOptions().toString(), userPrompt));

            // Stream content tokens from OpenAI. Each chunk is a ChatResponse object —
            // we extract the text and capture token usage from the last chunk.
            // IMPORTANT: doOnComplete closes the tool sink. Without this, mergeWith
            // below would wait for more tool events forever and the stream would hang.
            var content = ctx.request().stream().chatResponse()
                    .<EventStreamValueHolder>concatMap(chunk -> {
                        captureTokenUsage(chunk, promptTokensRef, completionTokensRef);
                        String text = getContentFromChatResponse(chunk);
                        return (text != null && !text.isEmpty())
                                ? Flux.just(new EventStreamValueHolder.Content(text))
                                : Flux.empty();
                    })
                    .doOnComplete(toolCallSink::tryEmitComplete);

            // Source URLs — Flux.defer because the document list is empty right now,
            // it gets filled during tool execution (which happens during content streaming).
            Flux<EventStreamValueHolder> sources = Flux.defer(() -> {
                List<String> urls = extractSourceUrls(ctx.retrievedDocuments());
                if (urls.isEmpty()) return Flux.empty();
                return emit(new EventStreamValueHolder.SourcesStart())
                        .concatWith(Flux.fromIterable(urls).map(EventStreamValueHolder.Metadata::new));
            });

            // Final summary — Flux.defer because token counts and duration
            // are only known after all content has been streamed.
            Flux<EventStreamValueHolder> summary = Flux.defer(() -> emit(
                    new EventStreamValueHolder.RequestEnd(promptTokensRef.get(), completionTokensRef.get(),
                            System.currentTimeMillis() - startTime)));

            // -- Assembly --
            // concatWith = "after this finishes, play the next one" (strict order)
            // mergeWith  = "play both at the same time, interleave as events arrive"
            //
            // Main sequence plays in order: info → tokens → sources → summary.
            // Tool events merge in alongside — they arrive during content streaming
            // but in practice all fire before the first content token.
            var mainSequence = requestInfo
                    .concatWith(emit(new EventStreamValueHolder.TokensStart()))
                    .concatWith(content)
                    .concatWith(emit(new EventStreamValueHolder.TokensEnd()))
                    .concatWith(sources)
                    .concatWith(summary);

            return toolEvents
                    .mergeWith(mainSequence);
        });

        // Wrap each event with conversationId for logging/persistence,
        // then apply cross-cutting diagnostics (console log + ChatLog save).
        String cid = conversationId != null ? conversationId : "";
        return withDiagnostics(stream.map(event -> StreamingEvent.of(cid, event)));
    }

    /** Wraps a single event as a Flux — DSL helper for readable stream assembly. */
    private static Flux<EventStreamValueHolder> emit(EventStreamValueHolder e) {
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
     * Cross-cutting diagnostics applied to every stream regardless of caller:
     * <ul>
     *   <li>{@code doOnNext} — logs each event to console with conversation id in MDC
     *       (via {@link #runWithConvId}), so logback pattern {@code [%X{cid}]} works</li>
     *   <li>{@code doOnComplete} — extracts all data from accumulated events and
     *       persists a ChatLog entity (no external mutable state — everything from events)</li>
     *   <li>{@code subscribeOn(streamingScheduler)} — runs the entire chain on a
     *       dedicated thread pool, keeping Tomcat servlet threads free</li>
     * </ul>
     */
    private Flux<StreamingEvent> withDiagnostics(Flux<StreamingEvent> stream) {
        List<StreamingEvent> eventLog = Collections.synchronizedList(new ArrayList<>());
        return stream
                .doOnNext(holder -> {
                    eventLog.add(holder);
                    runWithConvId(holder.conversationId(), () -> logEventToConsole(holder));
                })
                .doOnComplete(() -> persistChatLog(eventLog))
                .subscribeOn(streamingScheduler);
    }

    /**
     * Persists a ChatLog from accumulated stream events.
     * All data (conversationId, sources, tokens, duration, log lines) is extracted
     * from the events — no external mutable state needed.
     */
    private void persistChatLog(List<StreamingEvent> holders) {
        if (holders.isEmpty()) return;
        String conversationId = holders.getFirst().conversationId();
        List<String> logLines = new ArrayList<>();
        List<String> sourceUrls = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        long totalDurationMs = 0;

        for (StreamingEvent holder : holders) {
            String ts = formatTimestamp(holder.timestamp());
            switch (holder.value()) {
                case EventStreamValueHolder.RequestInfo ri ->
                    logLines.add("%s Model: %s, User prompt: %s".formatted(ts, ri.model(), ri.userPrompt()));
                case EventStreamValueHolder.ToolCallStart tc ->
                        logLines.add("%s >>> Using %s: %s".formatted(ts, tc.tool(), tc.query()));
                case EventStreamValueHolder.ToolRetrieved tr ->
                        logLines.add("%s Found documents (%d) in %d ms: %s".formatted(ts, tr.documents().size(), tr.durationMs(), formatDocScores(tr.documents())));
                case EventStreamValueHolder.ToolReranked tr ->
                        logLines.add("%s Reranked documents (%d) in %d ms: %s".formatted(ts, tr.documents().size(), tr.durationMs(), formatDocScores(tr.documents())));
                case EventStreamValueHolder.ToolCallEnd tc ->
                        logLines.add("%s %s done in %d ms".formatted(ts, tc.tool(), tc.totalDurationMs()));
                case EventStreamValueHolder.Metadata m -> sourceUrls.add(m.source());
                case EventStreamValueHolder.RequestEnd re -> {
                    promptTokens = re.promptTokens();
                    completionTokens = re.completionTokens();
                    totalDurationMs = re.totalDurationMs();
                    logLines.add("%s Received response in %d ms [promptTokens: %d, completionTokens: %d]"
                            .formatted(ts, re.totalDurationMs(), re.promptTokens(), re.completionTokens()));
                }
                default -> {}
            }
        }

        chatLogManager.saveStreamResponse(conversationId, logLines,
                sourceUrls.isEmpty() ? null : String.join(",", sourceUrls),
                promptTokens, completionTokens, (int) totalDurationMs);
    }

    /** Logs significant stream events to console with conversation id. */
    /** Logs significant stream events to console. MDC "cid" is set by runWithConvId. */
    private void logEventToConsole(StreamingEvent holder) {
        switch (holder.value()) {
            case EventStreamValueHolder.RequestInfo ri ->
                    log.info("Model: {}, User prompt: {}", ri.model(), abbreviate(ri.userPrompt(), 200));
            case EventStreamValueHolder.ToolCallStart tc ->
                    log.info(">>> Using {}: {}", tc.tool(), tc.query());
            case EventStreamValueHolder.ToolRetrieved tr ->
                    log.info("Found documents ({}): {}", tr.documents().size(), formatDocScores(tr.documents()));
            case EventStreamValueHolder.ToolReranked tr ->
                    log.info("Reranked documents ({}): {}", tr.documents().size(), formatDocScores(tr.documents()));
            case EventStreamValueHolder.ToolCallEnd tc ->
                    log.info("{} done in {} ms", tc.tool(), tc.totalDurationMs());
            case EventStreamValueHolder.RequestEnd re ->
                    log.info("Received response in {} ms [promptTokens: {}, completionTokens: {}]",
                            re.totalDurationMs(), re.promptTokens(), re.completionTokens());
            default -> {}
        }
    }

    /**
     * Sets MDC "cid" for the duration of the action, then cleans up.
     * MDC is thread-local, so we can't set it once for the whole stream —
     * reactive operators may run on different threads. Instead, we set/remove
     * it around each individual log call. This way logback pattern
     * {@code [%X{cid}]} shows the conversation id in every log line.
     */
    private static void runWithConvId(String conversationId, Runnable action) {
        String previous = MDC.get("cid");
        MDC.put("cid", conversationId);
        try {
            action.run();
        } finally {
            // Restore previous value instead of removing — tool execution may have
            // set MDC via onToolCallStart, and we don't want to clear it mid-execution.
            if (previous != null) {
                MDC.put("cid", previous);
            } else {
                MDC.remove("cid");
            }
        }
    }

    private static String formatTimestamp(Instant timestamp) {
        return LocalTime.ofInstant(timestamp, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static String formatDocScores(List<EventStreamValueHolder.DocScore> docs) {
        return docs.stream()
                .map(d -> "(%.3f) %s".formatted(d.score(), d.url()))
                .toList().toString();
    }

    private List<String> extractSourceUrls(List<Document> documents) {
        return documents.stream()
                .map(doc -> doc.getMetadata().get("url"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .toList();
    }

    private ToolEventListener createStreamingListener(Sinks.Many<EventStreamValueHolder> toolCallSink,
                                                       String conversationId) {
        return new ToolEventListener() {
            @Override
            public void onToolCallStart(String tool, String query) {
                MDC.put("cid", conversationId);
                toolCallSink.tryEmitNext(new EventStreamValueHolder.ToolCallStart(tool, query));
            }

            @Override
            public void onToolRetrieved(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                toolCallSink.tryEmitNext(new EventStreamValueHolder.ToolRetrieved(tool, documents, durationMs));
            }

            @Override
            public void onToolReranked(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                toolCallSink.tryEmitNext(new EventStreamValueHolder.ToolReranked(tool, documents, durationMs));
            }

            @Override
            public void onToolCallEnd(String tool, long totalDurationMs) {
                toolCallSink.tryEmitNext(new EventStreamValueHolder.ToolCallEnd(tool, totalDurationMs));
                MDC.remove("cid");
            }

            @Override
            public void onLog(String message) {
                log.debug(message);
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

    private static Prompt buildPrompt(String userPrompt, String systemPrompt) {
        return buildPrompt(userPrompt, systemPrompt, null);
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

    private ToolEventListener createLoggingListener(Consumer<String> internalLogger) {
        return new ToolEventListener() {
            @Override
            public void onToolCallStart(String tool, String query) {
                internalLogger.accept(">>> Using %s: %s".formatted(tool, query));
            }

            @Override
            public void onToolRetrieved(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                internalLogger.accept("Found documents (%d): %s".formatted(documents.size(), formatDocScores(documents)));
            }

            @Override
            public void onToolReranked(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                internalLogger.accept("Reranked documents (%d): %s".formatted(documents.size(), formatDocScores(documents)));
            }

            @Override
            public void onToolCallEnd(String tool, long totalDurationMs) {
                internalLogger.accept("%s done in %d ms".formatted(tool, totalDurationMs));
            }

            @Override
            public void onLog(String message) {
                internalLogger.accept(message);
            }
        };
    }

    static boolean shouldEnableTools(String userPrompt, ParametersReader parametersReader) {
        if (!parametersReader.getBoolean("tools.skipForTrivialPrompts", true)) {
            return true;
        }
        return QUERY_CLASSIFIER.isTechnicalPrompt(userPrompt);
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
