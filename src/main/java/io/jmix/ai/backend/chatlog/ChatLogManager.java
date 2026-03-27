package io.jmix.ai.backend.chatlog;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.ChatLog;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

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
        chatLog.setResponseTime(response.responseTime());
        dataManager.save(chatLog);
    }

    public void saveStreamResponse(String conversationId, List<String> logMessages,
                                    @Nullable String sources,
                                    int promptTokens, int completionTokens, int responseTime) {
        ChatLog chatLog = dataManager.create(ChatLog.class);
        chatLog.setConversationId(conversationId);
        chatLog.setContent(String.join("\n", logMessages));
        chatLog.setSources(sources);
        chatLog.setPromptTokens(promptTokens);
        chatLog.setCompletionTokens(completionTokens);
        chatLog.setResponseTime(responseTime);
        dataManager.save(chatLog);
    }

    public void saveError(String conversationId, String errorText) {
        ChatLog chatLog = dataManager.create(ChatLog.class);
        chatLog.setConversationId(conversationId);
        chatLog.setContent(errorText);
        dataManager.save(chatLog);
    }
}
