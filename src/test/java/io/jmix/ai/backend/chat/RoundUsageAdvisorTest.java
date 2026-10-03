package io.jmix.ai.backend.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoundUsageAdvisorTest {

    private static final String QUESTION = "How to show a notification?";
    private static final String ANSWER = "Use the Notifications bean.";
    private static final int PROMPT_TOKENS = 100;
    private static final int COMPLETION_TOKENS = 20;

    private final TurnTrace trace = new TurnTrace("conversation-1");
    private final RoundUsageAdvisor advisor = new RoundUsageAdvisor(0);

    @Mock
    private CallAdvisorChain callChain;
    @Mock
    private StreamAdvisorChain streamChain;

    @Test
    void addsTheUsageOfEveryStreamedRoundToTheTrace() {
        // Arrange
        when(streamChain.nextStream(any())).thenAnswer(invocation -> Flux.just(textResponse(), usageResponse()));
        advisor.adviseStream(request(), streamChain).blockLast();

        // Act
        advisor.adviseStream(request(), streamChain).blockLast();

        // Assert
        assertThat(List.of(trace.promptTokens(), trace.completionTokens()))
                .containsExactly(2 * PROMPT_TOKENS, 2 * COMPLETION_TOKENS);
    }

    @Test
    void addsTheUsageOfACallRoundToTheTrace() {
        // Arrange
        when(callChain.nextCall(any())).thenReturn(usageResponse());

        // Act
        advisor.adviseCall(request(), callChain);

        // Assert
        assertThat(List.of(trace.promptTokens(), trace.completionTokens()))
                .containsExactly(PROMPT_TOKENS, COMPLETION_TOKENS);
    }

    private ChatClientRequest request() {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(QUESTION)))
                .context(Map.of(TurnTrace.KEY, trace))
                .build();
    }

    private static ChatClientResponse textResponse() {
        return response(new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER)))));
    }

    private static ChatClientResponse usageResponse() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(PROMPT_TOKENS, COMPLETION_TOKENS))
                .build();
        return response(new ChatResponse(List.of(new Generation(new AssistantMessage(""))), metadata));
    }

    private static ChatClientResponse response(ChatResponse chatResponse) {
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .build();
    }
}
