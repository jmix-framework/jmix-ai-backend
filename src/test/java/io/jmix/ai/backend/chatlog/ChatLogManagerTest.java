package io.jmix.ai.backend.chatlog;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.ChatLog;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHAT_LOG.CONTENT embeds model text (the answer prefix, model-written tool queries); PostgreSQL
 * TEXT rejects NUL, so every save path must strip it.
 */
class ChatLogManagerTest {

    private final UnconstrainedDataManager dataManager = mock(UnconstrainedDataManager.class);
    private final ChatLogManager manager = new ChatLogManager(dataManager);

    private ChatLog stubCreate() {
        ChatLog chatLog = new ChatLog();
        when(dataManager.create(ChatLog.class)).thenReturn(chatLog);
        return chatLog;
    }

    @Test
    void saveResponse_StripsNulFromContent() {
        ChatLog chatLog = stubCreate();

        manager.saveResponse("cid", new Chat.StructuredResponse(
                "answer", List.of("Received: Default \u0000char value."), null, 1, 1, 1));

        assertThat(chatLog.getContent()).isEqualTo("Received: Default char value.");
    }

    @Test
    void saveStreamResponse_StripsNulFromContent() {
        ChatLog chatLog = stubCreate();

        manager.saveStreamResponse("cid", List.of("line \u0000one", "line two"), null, 1, 1, 1);

        assertThat(chatLog.getContent()).isEqualTo("line one\nline two");
    }

    @Test
    void saveError_StripsNulFromContent() {
        ChatLog chatLog = stubCreate();

        manager.saveError("cid", "boom \u0000happened");

        assertThat(chatLog.getContent()).isEqualTo("boom happened");
    }
}
