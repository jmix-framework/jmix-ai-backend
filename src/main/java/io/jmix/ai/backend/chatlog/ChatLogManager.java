package io.jmix.ai.backend.chatlog;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.ChatLog;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.stereotype.Component;

@Component
public class ChatLogManager {

    private final UnconstrainedDataManager dataManager;

    public ChatLogManager(UnconstrainedDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public void saveResponse(String conversationId, Chat.StructuredResponse response) {
        ChatLog chatLog = dataManager.create(ChatLog.class);
        chatLog.setConversationId(conversationId);
        chatLog.setContent(String.join("\n", response.logMessages()));
        chatLog.setSources(response.sourceLinks() != null ? String.join(",", response.sourceLinks()) : null);
        chatLog.setPromptTokens(response.promptTokens());
        chatLog.setCompletionTokens(response.completionTokens());
        dataManager.save(chatLog);
    }

    public void saveError(String conversationId, String errorText) {
        ChatLog chatLog = dataManager.create(ChatLog.class);
        chatLog.setConversationId(conversationId);
        chatLog.setContent(errorText);
        dataManager.save(chatLog);
    }
}
