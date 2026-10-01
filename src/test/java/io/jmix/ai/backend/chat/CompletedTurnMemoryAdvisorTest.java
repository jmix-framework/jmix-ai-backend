package io.jmix.ai.backend.chat;

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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.SYSTEM;
import static org.springframework.ai.chat.messages.MessageType.USER;

@ExtendWith(MockitoExtension.class)
class CompletedTurnMemoryAdvisorTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String SYSTEM_PROMPT = "You answer questions about Jmix.";

    private static final String FIRST_QUESTION = "How to use RichCodeEditor?";
    private static final String FIRST_ANSWER = "Add a richCodeEditor component to the view.";
    private static final String INTERRUPTED_QUESTION = "How to display Polygon in Map?";
    private static final String NEXT_QUESTION = "RichCodeEditor";
    private static final String NEXT_ANSWER = "RichCodeEditor edits source code.";

    private final ChatMemory memory = MessageWindowChatMemory.builder().build();
    private final CompletedTurnMemoryAdvisor advisor = new CompletedTurnMemoryAdvisor(memory);

    @Mock
    private CallAdvisorChain callChain;
    @Mock
    private StreamAdvisorChain streamChain;
    @Captor
    private ArgumentCaptor<ChatClientRequest> sentRequest;

    @Test
    void remembersAnAnsweredTurn() {
        // Arrange
        when(callChain.nextCall(any())).thenReturn(response(FIRST_ANSWER));

        // Act
        advisor.adviseCall(request(FIRST_QUESTION), callChain);

        // Assert
        assertThat(memory.get(CONVERSATION_ID))
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(USER, FIRST_QUESTION),
                        tuple(ASSISTANT, FIRST_ANSWER));
    }

    @Test
    void sendsTheHistoryAfterTheSystemPrompt() {
        // Arrange
        rememberFirstTurn();
        when(callChain.nextCall(sentRequest.capture())).thenReturn(response(NEXT_ANSWER));

        // Act
        advisor.adviseCall(request(NEXT_QUESTION), callChain);

        // Assert
        assertThat(sentRequest.getValue().prompt().getInstructions())
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(SYSTEM, SYSTEM_PROMPT),
                        tuple(USER, FIRST_QUESTION),
                        tuple(ASSISTANT, FIRST_ANSWER),
                        tuple(USER, NEXT_QUESTION));
    }

    @Test
    void keepsTheRequestOptionsWhenAddingTheHistory() {
        // Arrange
        rememberFirstTurn();
        ChatOptions options = ChatOptions.builder()
                .model("test-model")
                .build();
        when(callChain.nextCall(sentRequest.capture())).thenReturn(response(NEXT_ANSWER));

        // Act
        advisor.adviseCall(request(NEXT_QUESTION, options), callChain);

        // Assert
        ChatOptions sentOptions = sentRequest.getValue().prompt().getOptions();
        assertThat(sentOptions)
                .extracting(ChatOptions::getModel)
                .isEqualTo("test-model");
    }

    @Test
    void forgetsATurnWhoseCallFailed() {
        // Arrange
        rememberFirstTurn();
        when(callChain.nextCall(any())).thenThrow(new IllegalStateException("model failed"));

        // Act
        Throwable failure = catchThrowable(() -> advisor.adviseCall(request(INTERRUPTED_QUESTION), callChain));

        // Assert
        assertThat(failure)
                .hasMessage("model failed");
        assertThat(memory.get(CONVERSATION_ID))
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(USER, FIRST_QUESTION),
                        tuple(ASSISTANT, FIRST_ANSWER));
    }

    @Test
    void forgetsATurnWithABlankAnswer() {
        // Arrange
        when(callChain.nextCall(any())).thenReturn(response(" "));

        // Act
        advisor.adviseCall(request(FIRST_QUESTION), callChain);

        // Assert
        assertThat(memory.get(CONVERSATION_ID))
                .isEmpty();
    }

    @Test
    void remembersTheStreamedChunksAsOneAnswer() {
        // Arrange
        Flux<ChatClientResponse> chunks = Flux.just(
                response("Add a richCodeEditor "),
                response("component to the view."));
        when(streamChain.nextStream(any())).thenReturn(chunks);

        // Act
        advisor.adviseStream(request(FIRST_QUESTION), streamChain)
                .blockLast();

        // Assert
        assertThat(memory.get(CONVERSATION_ID))
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(USER, FIRST_QUESTION),
                        tuple(ASSISTANT, FIRST_ANSWER));
    }

    @Test
    void forgetsAStreamThatFailedMidway() {
        // Arrange
        Flux<ChatClientResponse> chunks = Flux.concat(
                Flux.just(response("Use the ")),
                Flux.error(new IllegalStateException("model failed")));
        when(streamChain.nextStream(any())).thenReturn(chunks);

        // Act
        Throwable failure = catchThrowable(() -> advisor.adviseStream(request(INTERRUPTED_QUESTION), streamChain)
                .blockLast());

        // Assert
        assertThat(failure)
                .hasMessageContaining("model failed");
        assertThat(memory.get(CONVERSATION_ID))
                .isEmpty();
    }

    @Test
    void forgetsAStreamTheClientCancelled() {
        // Arrange
        Flux<ChatClientResponse> chunks = Flux.concat(
                Flux.just(response("Use the ")),
                Flux.never());
        when(streamChain.nextStream(any())).thenReturn(chunks);

        // Act
        advisor.adviseStream(request(INTERRUPTED_QUESTION), streamChain)
                .take(1)
                .blockLast();

        // Assert
        assertThat(memory.get(CONVERSATION_ID))
                .isEmpty();
    }

    private void rememberFirstTurn() {
        memory.add(CONVERSATION_ID, List.of(
                new UserMessage(FIRST_QUESTION),
                new AssistantMessage(FIRST_ANSWER)));
    }

    private static ChatClientRequest request(String question) {
        return request(question, null);
    }

    private static ChatClientRequest request(String question, ChatOptions options) {
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(question)),
                options);
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(ChatMemory.CONVERSATION_ID, CONVERSATION_ID)
                .build();
    }

    private static ChatClientResponse response(String answer) {
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(ChatMemory.CONVERSATION_ID, CONVERSATION_ID)
                .build();
    }
}
