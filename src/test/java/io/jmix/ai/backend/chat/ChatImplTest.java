package io.jmix.ai.backend.chat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.retrieval.ToolsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatImplTest {

    private final JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
    private final ParametersRepository parametersRepository = mock(ParametersRepository.class);
    private final ToolsManager toolsManager = mock(ToolsManager.class);
    private final ChatLogManager chatLogManager = mock(ChatLogManager.class);
    private final SystemPromptResolver systemPromptResolver = mock(SystemPromptResolver.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);

    private ChatImpl chat;

    @BeforeEach
    void setUp() {
        when(chatMemoryRepository.findByConversationId(anyString())).thenReturn(List.of());
        when(parametersRepository.getReader("parameters"))
                .thenReturn(new ParametersReader(Map.of("systemMessage", "System prompt")));
        when(toolsManager.getTools(anyString(), any(), any(), any())).thenReturn(List.of());
        when(systemPromptResolver.resolve("System prompt", JmixVersion.V2)).thenReturn("Resolved prompt");
        when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModelFactory.build(any(ParametersReader.class))).thenReturn(chatModel);

        chat = new ChatImpl(chatMemoryRepository, parametersRepository, Schedulers.immediate(),
                toolsManager, chatLogManager, systemPromptResolver, chatModelFactory);
    }

    @Test
    void requestStream_EmitsOrderedEventsAndPersistsCompletedResponse() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("Hello", 0, 0),
                response(" world", 3, 2)
        ));

        List<StreamingEvent> events = chat.requestStream(
                        "Question", "parameters", "conversation-id", JmixVersion.V2)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.value().getClass().getSimpleName()).containsExactly(
                "RequestInfo", "TokensStart", "Content", "Content", "TokensEnd", "RequestEnd"
        );
        assertThat(events).extracting(StreamingEvent::conversationId)
                .containsOnly("conversation-id");
        assertThat(events).filteredOn(event -> event.value() instanceof EventStreamValueHolder.Content)
                .extracting(event -> ((EventStreamValueHolder.Content) event.value()).text())
                .containsExactly("Hello", " world");

        EventStreamValueHolder.RequestEnd requestEnd =
                (EventStreamValueHolder.RequestEnd) events.get(events.size() - 1).value();
        assertThat(requestEnd.promptTokens()).isEqualTo(3);
        assertThat(requestEnd.completionTokens()).isEqualTo(2);
        verify(chatLogManager).saveStreamResponse(
                eq("conversation-id"), any(), isNull(), eq(3), eq(2), anyInt());
    }

    @Test
    void requestStream_PropagatesModelErrorWithoutHanging() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("boom")));

        assertThatThrownBy(() -> chat.requestStream(
                        "Question", "parameters", "conversation-id", JmixVersion.V2)
                .collectList()
                .block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void requestStream_ConcurrentCallsKeepGeneratedConversationIdsAndMdcIsolated() {
        CountDownLatch bothStreamsSubscribed = new CountDownLatch(2);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.defer(() -> {
            bothStreamsSubscribed.countDown();
            try {
                if (!bothStreamsSubscribed.await(1, TimeUnit.SECONDS)) {
                    return Flux.error(new IllegalStateException("Concurrent stream did not start"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(e);
            }
            return Flux.just(response("answer", 3, 2)).delayElements(Duration.ofMillis(20));
        }));

        Scheduler concurrentScheduler = Schedulers.newParallel("chat-test", 2);
        ChatImpl concurrentChat = new ChatImpl(chatMemoryRepository, parametersRepository, concurrentScheduler,
                toolsManager, chatLogManager, systemPromptResolver, chatModelFactory);
        MdcCapturingAppender appender = new MdcCapturingAppender();
        Logger chatLogger = (Logger) LoggerFactory.getLogger(ChatImpl.class);
        appender.start();
        chatLogger.addAppender(appender);

        try {
            var result = Mono.zip(
                            concurrentChat.requestStream("First question", "parameters", null, JmixVersion.V2)
                                    .collectList(),
                            concurrentChat.requestStream("Second question", "parameters", null, JmixVersion.V2)
                                    .collectList())
                    .block(Duration.ofSeconds(2));

            assertThat(result).isNotNull();
            List<StreamingEvent> firstEvents = result.getT1();
            List<StreamingEvent> secondEvents = result.getT2();
            String firstCid = firstEvents.getFirst().conversationId();
            String secondCid = secondEvents.getFirst().conversationId();

            assertThat(firstCid).isNotBlank().isNotEqualTo(secondCid);
            assertThat(firstEvents).extracting(StreamingEvent::conversationId).containsOnly(firstCid);
            assertThat(secondEvents).extracting(StreamingEvent::conversationId).containsOnly(secondCid);
            assertThat(appender.cidForPrompt("First question")).isEqualTo(firstCid);
            assertThat(appender.cidForPrompt("Second question")).isEqualTo(secondCid);
            verify(chatLogManager).saveStreamResponse(
                    eq(firstCid), any(), isNull(), eq(3), eq(2), anyInt());
            verify(chatLogManager).saveStreamResponse(
                    eq(secondCid), any(), isNull(), eq(3), eq(2), anyInt());
        } finally {
            chatLogger.detachAppender(appender);
            appender.stop();
            concurrentScheduler.dispose();
        }
    }

    private static ChatResponse response(String text, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private static final class MdcCapturingAppender extends AppenderBase<ILoggingEvent> {

        private final ConcurrentMap<String, String> cidsByPrompt = new ConcurrentHashMap<>();

        @Override
        protected void append(ILoggingEvent event) {
            if (!"Model: {}, User prompt: {}".equals(event.getMessage())) {
                return;
            }
            Object[] arguments = event.getArgumentArray();
            if (arguments == null || arguments.length < 2) {
                return;
            }
            String cid = event.getMDCPropertyMap().get("cid");
            cidsByPrompt.put(arguments[1].toString(), cid != null ? cid : "<missing>");
        }

        private String cidForPrompt(String prompt) {
            return cidsByPrompt.get(prompt);
        }
    }
}
