package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolEventsAdvisorTest {

    private static final String QUESTION = "How to show a notification?";
    private static final String TOOL = "documentation_retriever";
    private static final String QUERY = "notifications";
    private static final String ANSWER = "Use the Notifications bean.";
    private static final String DOCUMENT_URL = "https://docs.jmix.io/notifications.html";
    private static final long RETRIEVAL_MS = 120;
    private static final long RERANK_MS = 300;
    private static final EventStreamValueHolder.RequestedRetrieval REQUESTED = new EventStreamValueHolder.RequestedRetrieval(6, 24);
    private static final List<EventStreamValueHolder.DocScore> DOCUMENTS = List.of(new EventStreamValueHolder.DocScore(0.9, DOCUMENT_URL));

    private final TurnTrace trace = new TurnTrace("conversation-1");
    private final ToolEventsAdvisor advisor = new ToolEventsAdvisor(0);

    @Mock
    private StreamAdvisorChain streamChain;

    @Test
    void streamsTheEventsOfAToolBeforeTheAnswerThatFollowsIt() {
        // Arrange
        RetrievalResult result = retrievalResult();
        when(streamChain.nextStream(any())).thenReturn(Flux.defer(() -> {
            trace.toolStarted(TOOL, QUERY, REQUESTED);
            trace.addRetrieval(result);
            return Flux.just(textResponse(ANSWER));
        }));

        // Act
        List<ChatClientResponse> responses = advisor.adviseStream(request(), streamChain).collectList().block();

        // Assert
        assertThat(responses)
                .extracting(response -> ToolEventsAdvisor.eventOf(response.chatResponse()))
                .containsExactly(
                        new EventStreamValueHolder.ToolCallStart(TOOL, QUERY, REQUESTED),
                        new EventStreamValueHolder.ToolRetrieved(TOOL, DOCUMENTS, RETRIEVAL_MS),
                        new EventStreamValueHolder.ToolReranked(TOOL, DOCUMENTS, RERANK_MS),
                        new EventStreamValueHolder.ToolCallEnd(TOOL, result.durationMs()),
                        null);
    }

    @Test
    void completesWithTheModelStream() {
        // Arrange
        when(streamChain.nextStream(any())).thenReturn(Flux.just(textResponse(ANSWER)));

        // Act
        List<ChatClientResponse> responses = advisor.adviseStream(request(), streamChain).collectList().block();

        // Assert
        assertThat(responses)
                .extracting(response -> response.chatResponse().getResult().getOutput().getText())
                .containsExactly(ANSWER);
    }

    private ChatClientRequest request() {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(QUESTION)))
                .context(Map.of(TurnTrace.KEY, trace))
                .build();
    }

    private static ChatClientResponse textResponse(String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .build();
    }

    private static RetrievalResult retrievalResult() {
        Instant startedAt = Instant.parse("2026-10-03T10:00:00Z");
        return new RetrievalResult(TOOL, QUERY, REQUESTED, startedAt,
                List.of(
                        new RetrievalResult.Retrieved(startedAt.plusMillis(RETRIEVAL_MS), DOCUMENTS, RETRIEVAL_MS),
                        new RetrievalResult.Reranked(startedAt.plusMillis(RETRIEVAL_MS + RERANK_MS), DOCUMENTS, RERANK_MS)),
                List.of(), "", startedAt.plusMillis(RETRIEVAL_MS + RERANK_MS));
    }
}
