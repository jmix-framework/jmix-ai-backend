package test_support.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class ScriptedChatModel implements ChatModel {

    public static final String FAILURE_MESSAGE = "model failed";

    private final Deque<Supplier<Flux<ChatResponse>>> replies = new ArrayDeque<>();
    private final List<Prompt> receivedPrompts = new ArrayList<>();

    public void repliesWith(String answer) {
        replies.add(() -> Flux.just(response(answer)));
    }

    public void fails() {
        replies.add(() -> Flux.error(new IllegalStateException(FAILURE_MESSAGE)));
    }

    public void streamsChunks(String... chunks) {
        replies.add(() -> Flux.fromIterable(Arrays.stream(chunks).map(ScriptedChatModel::response).toList()));
    }

    public void streamsChunkThenFails(String chunk) {
        replies.add(() -> Flux.concat(
                Flux.just(response(chunk)),
                Flux.error(new IllegalStateException(FAILURE_MESSAGE))));
    }

    public void streamsChunkThenHangs(String chunk) {
        replies.add(() -> Flux.concat(
                Flux.just(response(chunk)),
                Flux.never()));
    }

    public Prompt lastPrompt() {
        return receivedPrompts.getLast();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return Objects.requireNonNull(nextReply(prompt).blockLast());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return nextReply(prompt);
    }

    private Flux<ChatResponse> nextReply(Prompt prompt) {
        receivedPrompts.add(prompt);
        Supplier<Flux<ChatResponse>> reply = replies.removeFirst();
        return reply.get();
    }

    private static ChatResponse response(String text) {
        AssistantMessage message = new AssistantMessage(text);
        return new ChatResponse(List.of(new Generation(message)));
    }
}
