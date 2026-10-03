package io.jmix.ai.backend.chat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCallLoggingAdvisorTest {

    private static final String QUESTION = "How to show a notification?";
    private static final String ANSWER_START = "Use the ";
    private static final String ANSWER_END = "Notifications bean.";

    private final ModelCallLoggingAdvisor advisor = new ModelCallLoggingAdvisor(0);
    private final Logger logger = (Logger) LoggerFactory.getLogger(ModelCallLoggingAdvisor.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @Mock
    private CallAdvisorChain callChain;
    @Mock
    private StreamAdvisorChain streamChain;

    @BeforeEach
    void setUp() {
        logger.setLevel(Level.TRACE);
        logger.addAppender(logged);
        logged.start();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
        logger.setLevel(null);
    }

    @Test
    void tracesTheRequestAndTheResponseOfACall() {
        // Arrange
        when(callChain.nextCall(any())).thenReturn(response(ANSWER_START + ANSWER_END));

        // Act
        advisor.adviseCall(request(), callChain);

        // Assert
        assertThat(logged.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.TRACE, Level.TRACE);
        assertThat(logged.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .satisfiesExactly(
                        request -> assertThat(request)
                                .startsWith("LLM Request:\n")
                                .contains(QUESTION),
                        response -> assertThat(response)
                                .startsWith("LLM Response:\n")
                                .contains(ANSWER_START + ANSWER_END));
    }

    @Test
    void tracesTheWholeStreamedResponseOnce() {
        // Arrange
        when(streamChain.nextStream(any())).thenReturn(Flux.just(response(ANSWER_START), response(ANSWER_END)));

        // Act
        advisor.adviseStream(request(), streamChain).blockLast();

        // Assert
        assertThat(logged.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .satisfiesExactly(
                        request -> assertThat(request)
                                .startsWith("LLM Request:\n"),
                        response -> assertThat(response)
                                .startsWith("LLM Response:\n")
                                .contains(ANSWER_START + ANSWER_END));
    }

    private static ChatClientRequest request() {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(QUESTION)))
                .build();
    }

    private static ChatClientResponse response(String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .build();
    }
}
