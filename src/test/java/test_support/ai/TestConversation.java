package test_support.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.function.Function;

/**
 * One conversation over a {@link ChatClient}: every question is sent with the same conversation id,
 * so the client's memory links the turns.
 */
public class TestConversation {

    private final ChatClient client;
    private final String conversationId;
    private final Function<String, Prompt> promptForQuestion;

    public TestConversation(ChatClient client,
                            String conversationId,
                            Function<String, Prompt> promptForQuestion) {
        this.client = client;
        this.conversationId = conversationId;
        this.promptForQuestion = promptForQuestion;
    }

    public void ask(String question) {
        request(question)
                .call()
                .chatResponse();
    }

    public void ask(String question, ChatOptions options) {
        request(question)
                .options(options)
                .call()
                .chatResponse();
    }

    public void askStreaming(String question) {
        streamedChunks(question)
                .blockLast();
    }

    /** Takes the first streamed chunk and cancels the rest of the stream. */
    public String askStreamingAndAbandonAfterFirstChunk(String question) {
        ChatResponse firstChunk = streamedChunks(question)
                .take(1)
                .blockLast();
        return text(Objects.requireNonNull(firstChunk));
    }

    private Flux<ChatResponse> streamedChunks(String question) {
        return request(question)
                .stream()
                .chatResponse();
    }

    private ChatClient.ChatClientRequestSpec request(String question) {
        Prompt prompt = promptForQuestion.apply(question);
        return client.prompt(prompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));
    }

    private static String text(ChatResponse response) {
        return response.getResult()
                .getOutput()
                .getText();
    }
}
