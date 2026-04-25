package io.jmix.ai.backend.chat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHistoryServiceTest {

    @Test
    void loadConversationHistory_returnsOnlyRenderableMessages() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("from spring_ai_chat_memory"), org.mockito.ArgumentMatchers.eq("conversation-id")))
                .thenReturn(List.of(
                        Map.of("content", "Привет", "type", "USER"),
                        Map.of("content", "Здравствуйте", "type", "ASSISTANT"),
                        Map.of("content", "tool payload", "type", "TOOL")
                ));

        ChatHistoryService service = new ChatHistoryService(jdbcTemplate);

        assertThat(service.loadConversationHistory(" conversation-id "))
                .containsExactly(
                        new ChatHistoryService.ChatHistoryMessage("Привет", ChatHistoryService.ChatHistoryMessageType.USER),
                        new ChatHistoryService.ChatHistoryMessage("Здравствуйте", ChatHistoryService.ChatHistoryMessageType.ASSISTANT)
                );
    }

    @Test
    void normalizeConversationId_trimsBlankValues() {
        assertThat(ChatHistoryService.normalizeConversationId("  abc  ")).isEqualTo("abc");
        assertThat(ChatHistoryService.normalizeConversationId("   ")).isNull();
    }

    @Test
    void chatHistoryMessageType_ignoresUnsupportedTypes() {
        assertThat(ChatHistoryService.ChatHistoryMessageType.fromDatabaseValue("USER"))
                .isEqualTo(ChatHistoryService.ChatHistoryMessageType.USER);
        assertThat(ChatHistoryService.ChatHistoryMessageType.fromDatabaseValue("ASSISTANT"))
                .isEqualTo(ChatHistoryService.ChatHistoryMessageType.ASSISTANT);
        assertThat(ChatHistoryService.ChatHistoryMessageType.fromDatabaseValue("SYSTEM")).isNull();
    }
}
