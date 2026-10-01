package io.jmix.ai.backend.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Chat model stub: plays back one scripted reply per request and records the prompts it received. */
class ScriptedChatModel implements ChatModel {

    final List<Prompt> prompts = new ArrayList<>();
    private final Deque<Supplier<Flux<ChatResponse>>> script = new ArrayDeque<>();

    static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    ScriptedChatModel answers(String text) {
        return streams(() -> Flux.just(response(text)));
    }

    ScriptedChatModel fails() {
        return streams(() -> Flux.error(new IllegalStateException("model failed")));
    }

    ScriptedChatModel streams(Supplier<Flux<ChatResponse>> reply) {
        script.add(reply);
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return Objects.requireNonNull(next(prompt).blockLast());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return next(prompt);
    }

    private Flux<ChatResponse> next(Prompt prompt) {
        prompts.add(prompt);
        return script.removeFirst().get();
    }
}
