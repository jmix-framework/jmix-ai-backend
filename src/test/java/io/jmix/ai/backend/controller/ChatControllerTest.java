package io.jmix.ai.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chat.EventStreamValueHolder;
import io.jmix.ai.backend.chat.StreamingEvent;
import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.dto.StreamEventDto;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private Chat chat;
    @Mock
    private ParametersRepository parametersRepository;
    @Mock
    private ChatLogManager chatLogManager;

    @Test
    void streamReturnsGeneratedConversationIdAndUsesItForChat() throws Exception {
        Parameters parameters = new Parameters();
        parameters.setContent("parameters");
        when(parametersRepository.loadActive(ParametersTargetType.CHAT)).thenReturn(parameters);
        when(chat.requestStream(eq("question"), eq("parameters"), anyString(), eq(JmixVersion.V2)))
                .thenAnswer(invocation -> Flux.just(StreamingEvent.of(
                        invocation.getArgument(2), new EventStreamValueHolder.TokensStart())));
        ChatController controller = new ChatController(chat, parametersRepository, chatLogManager);
        ReflectionTestUtils.setField(controller, "maxRequestLength", 1_000);

        List<StreamEventDto> events = controller.chatStream(
                        new ChatController.Request(null, "question", null, null))
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        StreamEventDto.Conversation conversation = (StreamEventDto.Conversation) events.get(0);
        assertThat(UUID.fromString(conversation.conversationId())).isNotNull();
        assertThat(events.get(1)).isInstanceOf(StreamEventDto.TokensStart.class);
        verify(chat).requestStream("question", "parameters", conversation.conversationId(), JmixVersion.V2);

        String json = new ObjectMapper().writerFor(StreamEventDto.class).writeValueAsString(conversation);
        assertThat(json).contains("\"type\":\"conversation\"")
                .contains("\"conversation_id\":\"" + conversation.conversationId() + "\"");
    }
}
