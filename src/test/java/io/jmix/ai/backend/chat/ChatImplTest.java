package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.retrieval.ToolsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    private ChatImpl chat;

    @BeforeEach
    void setUp() {
        when(chatMemoryRepository.findByConversationId(anyString())).thenReturn(List.of());
        when(parametersRepository.getReader("parameters"))
                .thenReturn(new ParametersReader(Map.of("systemMessage", "System prompt")));
        when(toolsManager.getTools(anyString(), any(), any(), any())).thenReturn(List.of());
        when(systemPromptResolver.resolve("System prompt", JmixVersion.V2)).thenReturn("Resolved prompt");
        when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());

        chat = new ChatImpl(chatMemoryRepository, parametersRepository, Schedulers.immediate(),
                toolsManager, chatLogManager, systemPromptResolver, ignored -> chatModel);
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

    private static ChatResponse response(String text, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }
}
