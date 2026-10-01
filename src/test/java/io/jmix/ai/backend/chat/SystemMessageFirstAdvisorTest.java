package io.jmix.ai.backend.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemMessageFirstAdvisorTest {

    private static final String SYSTEM_PROMPT = "# Role and Objective";
    private static final String CONVERSATION_ID = "conv-1";
    private static final String FIRST_QUESTION = "How to use RichCodeEditor?";
    private static final String SECOND_QUESTION = "How to display Polygon in Map?";

    @Test
    void reorder_movesSystemMessagesAheadKeepingRelativeOrder() {
        ChatClientRequest request = requestOf(
                new UserMessage("q1"),
                new AssistantMessage("a1"),
                new SystemMessage("s1"),
                new UserMessage("q2"),
                new SystemMessage("s2"));

        List<Message> messages = SystemMessageFirstAdvisor.reorder(request).prompt().getInstructions();

        assertThat(messages).extracting(Message::getText)
                .containsExactly("s1", "s2", "q1", "a1", "q2");
    }

    @Test
    void reorder_keepsRequestWhenAlreadyOrdered() {
        ChatClientRequest request = requestOf(new SystemMessage("s"), new UserMessage("q"));

        assertThat(SystemMessageFirstAdvisor.reorder(request)).isSameAs(request);
    }

    @Test
    void call_sendsSystemPromptFirstOnFollowUpTurn() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = ChatImpl.buildClient(model, newMemory());

        callTurn(client, FIRST_QUESTION);
        callTurn(client, SECOND_QUESTION);

        assertFollowUpPrompt(model.prompts.get(1));
    }

    @Test
    void stream_sendsSystemPromptFirstOnFollowUpTurn() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = ChatImpl.buildClient(model, newMemory());

        streamTurn(client, FIRST_QUESTION);
        streamTurn(client, SECOND_QUESTION);

        assertFollowUpPrompt(model.prompts.get(1));
    }

    private static void assertFollowUpPrompt(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        assertThat(messages).extracting(Message::getMessageType).containsExactly(
                MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(messages.getFirst().getText()).isEqualTo(SYSTEM_PROMPT);
        assertThat(messages.subList(1, messages.size())).extracting(Message::getText)
                .containsExactly(FIRST_QUESTION, "answer 1", SECOND_QUESTION);
    }

    private static void callTurn(ChatClient client, String question) {
        client.prompt(ChatImpl.buildPrompt(question, SYSTEM_PROMPT))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .chatResponse();
    }

    private static void streamTurn(ChatClient client, String question) {
        client.prompt(ChatImpl.buildPrompt(question, SYSTEM_PROMPT))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .stream()
                .chatResponse()
                .blockLast();
    }

    private static ChatMemory newMemory() {
        return MessageWindowChatMemory.builder().maxMessages(10).build();
    }

    private static ChatClientRequest requestOf(Message... messages) {
        return ChatClientRequest.builder().prompt(new Prompt(List.of(messages))).build();
    }

    private static class RecordingChatModel implements ChatModel {

        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            return respond(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(respond(prompt));
        }

        private ChatResponse respond(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer " + prompts.size()))));
        }
    }
}
