package io.jmix.ai.backend.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatClientFactoryTest {

    private static final String SYSTEM_PROMPT = "# Role and Objective";

    @Test
    void createChatClient_remembersOnlyAnsweredTurns() {
        ScriptedChatModel model = new ScriptedChatModel().answers("answer A").fails().answers("answer C");
        ChatClient client = new ChatClientFactory(new InMemoryChatMemoryRepository()).createChatClient(model);

        ask(client, "A");
        assertThatThrownBy(() -> ask(client, "B")).hasStackTraceContaining("model failed");
        ask(client, "C");

        assertThat(model.prompts.get(2).getInstructions()).extracting(Message::getText)
                .containsExactly(SYSTEM_PROMPT, "A", "answer A", "C");
    }

    private static void ask(ChatClient client, String question) {
        client.prompt(new Prompt(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(question))))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-1"))
                .call()
                .chatResponse();
    }
}
