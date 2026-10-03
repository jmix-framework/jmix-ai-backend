package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.retrieval.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatLogAdvisorTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String MODEL = "OpenAiChatOptions: {model: gpt-5}";
    private static final String QUESTION = "How to show a notification?";
    private static final String ANSWER = "Use the Notifications bean.";
    private static final String TOOL = "documentation_retriever";
    private static final String DOCUMENT_URL = "https://docs.jmix.io/notifications.html";
    private static final String MODEL_FAILURE = "model failed";
    private static final int PROMPT_TOKENS = 100;
    private static final int COMPLETION_TOKENS = 20;

    private final TurnTrace trace = TurnTrace.forStream(CONVERSATION_ID);

    @Mock
    private ChatLogManager chatLogManager;
    @Mock
    private CallAdvisorChain callChain;
    @Mock
    private StreamAdvisorChain streamChain;
    @Captor
    private ArgumentCaptor<List<String>> savedLines;

    private ChatLogAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ChatLogAdvisor(chatLogManager, Schedulers.immediate(), 0);
        trace.addRetrieval(retrievalWithOneDocument());
        trace.addUsage(new DefaultUsage(PROMPT_TOKENS, COMPLETION_TOKENS));
    }

    @Test
    void doesNotSaveTheCallLogWhenSavingIsOff() {
        // Arrange
        when(callChain.nextCall(any())).thenReturn(response(ANSWER));

        // Act
        advisor.adviseCall(request(false), callChain);

        // Assert
        verify(chatLogManager, never()).save(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void savesAFailedCallAndRethrowsTheFailure() {
        // Arrange
        when(callChain.nextCall(any())).thenThrow(new IllegalStateException(MODEL_FAILURE));

        // Act
        Throwable failure = catchThrowable(() -> advisor.adviseCall(request(true), callChain));

        // Assert
        verify(chatLogManager).save(eq(CONVERSATION_ID), savedLines.capture(), any(), anyInt(), anyInt(), anyInt());
        assertThat(failure)
                .hasMessage(MODEL_FAILURE);
        assertThat(savedLines.getValue().getLast())
                .contains("Request failed: ", MODEL_FAILURE);
    }

    @Test
    void savesACancelledStream() {
        // Arrange
        when(streamChain.nextStream(any())).thenReturn(Flux.never());

        // Act
        advisor.adviseStream(request(true), streamChain).subscribe().dispose();

        // Assert
        verify(chatLogManager).save(eq(CONVERSATION_ID), savedLines.capture(), any(), anyInt(), anyInt(), anyInt());
        assertThat(savedLines.getValue().getLast())
                .endsWith("Request cancelled");
    }

    @Test
    void savesAFailedStream() {
        // Arrange
        when(streamChain.nextStream(any())).thenReturn(Flux.error(new IllegalStateException(MODEL_FAILURE)));

        // Act
        advisor.adviseStream(request(true), streamChain).onErrorComplete().blockLast();

        // Assert
        verify(chatLogManager).save(eq(CONVERSATION_ID), savedLines.capture(), any(), anyInt(), anyInt(), anyInt());
        assertThat(savedLines.getValue().getLast())
                .contains("Request failed: ", MODEL_FAILURE);
    }

    private ChatClientRequest request(boolean saveChatLog) {
        Map<String, Object> context = new HashMap<>();
        context.put(TurnTrace.KEY, trace);
        context.put(ChatLogAdvisor.MODEL, MODEL);
        context.put(ChatLogAdvisor.SAVE, saveChatLog);
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(QUESTION)))
                .context(context)
                .build();
    }

    private static ChatClientResponse response(String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .build();
    }

    private static RetrievalResult retrievalWithOneDocument() {
        Instant now = Instant.now();
        Document document = Document.builder()
                .id("doc-1")
                .text("Notifications")
                .metadata(Map.of("url", DOCUMENT_URL))
                .score(0.9)
                .build();
        return new RetrievalResult(TOOL, "notifications", null, now, List.of(), List.of(document), "Notifications", now);
    }
}
