package io.jmix.ai.backend.chat;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ChatHistoryService {

    private static final String LOAD_CONVERSATION_HISTORY_SQL = """
            select content, type
            from spring_ai_chat_memory
            where conversation_id = ?
            order by timestamp
            """;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ChatHistoryService(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    ChatHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ChatHistoryMessage> loadConversationHistory(@Nullable String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        if (normalizedConversationId == null) {
            return List.of();
        }

        return jdbcTemplate.queryForList(LOAD_CONVERSATION_HISTORY_SQL, normalizedConversationId).stream()
                .map(ChatHistoryService::toChatHistoryMessage)
                .filter(message -> message.type() != null)
                .toList();
    }

    @Nullable
    static String normalizeConversationId(@Nullable String conversationId) {
        return StringUtils.trimToNull(conversationId);
    }

    private static ChatHistoryMessage toChatHistoryMessage(Map<String, Object> row) {
        return new ChatHistoryMessage(
                Objects.toString(row.get("content"), ""),
                ChatHistoryMessageType.fromDatabaseValue(Objects.toString(row.get("type"), null))
        );
    }

    public record ChatHistoryMessage(String content, @Nullable ChatHistoryMessageType type) {
    }

    public enum ChatHistoryMessageType {
        USER,
        ASSISTANT;

        @Nullable
        static ChatHistoryMessageType fromDatabaseValue(@Nullable String value) {
            if (StringUtils.equals(value, USER.name())) {
                return USER;
            }
            if (StringUtils.equals(value, ASSISTANT.name())) {
                return ASSISTANT;
            }
            return null;
        }
    }
}
