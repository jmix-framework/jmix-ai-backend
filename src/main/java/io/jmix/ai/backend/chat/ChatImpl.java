package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.chat.springai.ToolCallingAdvisor;
import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.retrieval.AbstractRagTool;
import io.jmix.ai.backend.retrieval.RetrievalUtils;
import io.jmix.ai.backend.retrieval.ToolsManager;
import io.jmix.core.UuidProvider;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ChatImpl implements Chat {

    private static final int CHAT_LOG_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;
    private static final int TOOL_LOOP_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;
    private static final int TOOL_EVENTS_ORDER = TOOL_LOOP_ORDER - 1;
    private static final int ROUND_USAGE_ORDER = TOOL_LOOP_ORDER + 1;
    private static final int MODEL_CALL_LOGGING_ORDER = TOOL_LOOP_ORDER + 2;

    private final ParametersRepository parametersRepository;
    private final ChatMemory chatMemory;
    private final ToolsManager toolsManager;
    private final ChatLogManager chatLogManager;
    private final Scheduler streamingScheduler;
    private final SystemPromptResolver systemPromptResolver;

    public ChatImpl(ChatMemoryRepository chatMemoryRepository,
                    ParametersRepository parametersRepository,
                    @Qualifier("streamingScheduler") Scheduler streamingScheduler,
                    ToolsManager toolsManager,
                    ChatLogManager chatLogManager,
                    SystemPromptResolver systemPromptResolver) {
        this.parametersRepository = parametersRepository;
        this.streamingScheduler = streamingScheduler;
        this.chatLogManager = chatLogManager;
        this.systemPromptResolver = systemPromptResolver;

        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();

        this.toolsManager = toolsManager;
    }

    private record ChatRequestContext(
            ChatModel chatModel,
            ChatClient.ChatClientRequestSpec request,
            TurnTrace trace
    ) {
    }

    private ChatRequestContext prepareRequest(String userPrompt, String parametersYaml,
                                              @Nullable String conversationId,
                                              JmixVersion jmixVersion,
                                              TurnTrace trace,
                                              boolean saveChatLog) {
        String nonNullConversationId = conversationId != null
                ? conversationId : UuidProvider.createUuid().toString();

        ParametersReader parametersReader = parametersRepository.getReader(parametersYaml);
        ChatModel chatModel = buildChatModel(parametersReader);
        ChatClient chatClient = buildClient(chatModel);

        List<AbstractRagTool> tools = toolsManager.getTools(parametersYaml, jmixVersion);

        String systemMessageTemplate = parametersReader.getString("systemMessage");
        String systemPrompt = systemPromptResolver.resolve(systemMessageTemplate, jmixVersion);

        ChatClient.ChatClientRequestSpec request = chatClient.prompt(buildPrompt(userPrompt, systemPrompt));
        request.advisors(a -> a
                .param(ChatMemory.CONVERSATION_ID, nonNullConversationId)
                .param(TurnTrace.KEY, trace)
                .param(ChatLogAdvisor.MODEL, chatModel.getDefaultOptions().toString())
                .param(ChatLogAdvisor.SAVE, saveChatLog));
        request.toolCallbacks(tools.stream().map(AbstractRagTool::getToolCallback).toList());
        request.toolContext(Map.of(TurnTrace.KEY, trace));

        return new ChatRequestContext(chatModel, request, trace);
    }

    @Override
    public StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                                @Nullable JmixVersion jmixVersion, boolean saveChatLog) {
        long start = System.currentTimeMillis();
        JmixVersion version = jmixVersion != null ? jmixVersion : JmixVersion.V2;
        TurnTrace trace = new TurnTrace(conversationId);

        ChatRequestContext ctx = prepareRequest(userPrompt, parametersYaml, conversationId, version, trace, saveChatLog);
        ChatClientResponse response = ctx.request().call().chatClientResponse();

        String responseText = Objects.requireNonNullElse(getContentFromChatResponse(response.chatResponse()), "");
        return new StructuredResponse(
                responseText,
                linesOf(response, ChatLogAdvisor.LOG_LINES),
                linesOf(response, ChatLogAdvisor.RETRIEVAL_LINES),
                RetrievalUtils.getUniqueSortedDocuments(trace.documents()),
                trace.promptTokens(),
                trace.completionTokens(),
                (int) (System.currentTimeMillis() - start));
    }

    /**
     * Streams the assistant response as a sequence of typed {@link EventStreamValueHolder}s via SSE.
     *
     * <p>Events always arrive in this order:
     * <pre>
     * RequestInfo
     *   → [ToolCallStart* → (ToolRetrieved → ToolReranked → ToolCallEnd)*]*
     *   → TokensStart → Content* → TokensEnd
     *   → [SourcesStart → Metadata*]
     *   → RequestEnd
     * </pre>
     *
     * <p>Tool events come from {@link ToolEventsAdvisor} as part of the model stream; console logging
     * and ChatLog persistence are done by {@link ChatLogAdvisor}.
     */
    @Override
    public Flux<StreamingEvent> requestStream(String userPrompt,
                                              String parametersYaml,
                                              @Nullable String conversationId,
                                              @Nullable JmixVersion jmixVersion) {
        String cid = conversationId != null ? conversationId : "";
        Flux<EventStreamValueHolder> stream = Flux.defer(() -> {
            long startTime = System.currentTimeMillis();
            JmixVersion version = jmixVersion != null ? jmixVersion : JmixVersion.V2;
            TurnTrace trace = new TurnTrace(cid);
            ChatRequestContext ctx = prepareRequest(userPrompt, parametersYaml, conversationId, version, trace, true);

            AtomicBoolean tokensStarted = new AtomicBoolean();
            Flux<EventStreamValueHolder> answer = ctx.request().stream().chatResponse()
                    .concatMap(chunk -> {
                        EventStreamValueHolder toolEvent = ToolEventsAdvisor.eventOf(chunk);
                        if (toolEvent != null) {
                            return emit(toolEvent);
                        }
                        String text = getContentFromChatResponse(chunk);
                        if (text == null || text.isEmpty()) {
                            return Flux.empty();
                        }
                        Flux<EventStreamValueHolder> content = emit(new EventStreamValueHolder.Content(text));
                        return tokensStarted.compareAndSet(false, true)
                                ? emit(new EventStreamValueHolder.TokensStart()).concatWith(content)
                                : content;
                    });

            Flux<EventStreamValueHolder> tokensEnd = Flux.defer(() -> tokensStarted.getAndSet(true)
                    ? emit(new EventStreamValueHolder.TokensEnd())
                    : emit(new EventStreamValueHolder.TokensStart()).concatWith(emit(new EventStreamValueHolder.TokensEnd())));

            Flux<EventStreamValueHolder> sources = Flux.defer(() -> {
                List<String> urls = extractSourceUrls(trace.documents());
                if (urls.isEmpty()) return Flux.empty();
                return emit(new EventStreamValueHolder.SourcesStart())
                        .concatWith(Flux.fromIterable(urls).map(EventStreamValueHolder.Metadata::new));
            });

            Flux<EventStreamValueHolder> summary = Flux.defer(() -> emit(
                    new EventStreamValueHolder.RequestEnd(trace.promptTokens(), trace.completionTokens(),
                            System.currentTimeMillis() - startTime)));

            return emit(new EventStreamValueHolder.RequestInfo(ctx.chatModel().getDefaultOptions().toString(), userPrompt))
                    .concatWith(answer)
                    .concatWith(tokensEnd)
                    .concatWith(sources)
                    .concatWith(summary);
        });

        return stream
                .map(event -> StreamingEvent.of(cid, event))
                .subscribeOn(streamingScheduler);
    }

    private static Flux<EventStreamValueHolder> emit(EventStreamValueHolder e) {
        return Flux.just(e);
    }

    @SuppressWarnings("unchecked")
    private static List<String> linesOf(ChatClientResponse response, String key) {
        Object lines = response.context().get(key);
        return lines instanceof List<?> list ? (List<String>) list : List.of();
    }

    private List<String> extractSourceUrls(List<Document> documents) {
        return documents.stream()
                .map(doc -> doc.getMetadata().get("url"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .toList();
    }

    @Nullable
    private static String getContentFromChatResponse(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    Prompt buildPrompt(String userPrompt, String systemPrompt) {
        return new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));
    }

    ChatModel buildChatModel(ParametersReader parametersReader) {
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
                .build();
    }

    ChatClient buildClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        new ChatLogAdvisor(chatLogManager, streamingScheduler, CHAT_LOG_ORDER),
                        new CompletedTurnMemoryAdvisor(chatMemory),
                        new ToolEventsAdvisor(TOOL_EVENTS_ORDER),
                        ToolCallingAdvisor.builder()
                                .advisorOrder(TOOL_LOOP_ORDER)
                                .build(),
                        new RoundUsageAdvisor(ROUND_USAGE_ORDER),
                        new ModelCallLoggingAdvisor(MODEL_CALL_LOGGING_ORDER))
                .build();
    }
}
