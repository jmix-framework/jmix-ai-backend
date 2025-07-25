package io.jmix.ai.backend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chat.ChatImpl;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ChatController {

    private final Chat chat;
    private final ParametersRepository parametersRepository;

    public ChatController(Chat chat, ParametersRepository parametersRepository) {
        this.chat = chat;
        this.parametersRepository = parametersRepository;
    }

    @PostMapping("/chat")
    public Response chat(@RequestBody Request request) {
        Parameters parameters = parametersRepository.loadActive();
        ChatImpl.StructuredResponse chatResponse = chat.requestStructured(request.text(), parameters.getContent(), request.conversationId(), null);
        return new Response(request.text(),
                chatResponse.text(),
                "",
                chatResponse.sourceLinks() != null ? chatResponse.sourceLinks() : List.of());
    }

    public record Response(
            String input,
            String output,
            @JsonProperty("query_category") String queryCategory,
            List<String> sources) {
    }

    public record Request(
            @JsonProperty("conversation_id") String conversationId,
            String text,
            @JsonProperty("cache_enabled") Boolean cacheEnabled) {
    }
}
