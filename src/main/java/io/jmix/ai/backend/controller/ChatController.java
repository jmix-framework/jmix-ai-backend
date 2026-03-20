package io.jmix.ai.backend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chat.StreamEvent;
import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
public class ChatController {

    private static final String REQUEST_TEXT_IS_EMPTY_OR_BLANK = "Request text is empty or blank";
    private static final String REQUEST_TEXT_IS_TOO_LONG = "Request text is too long";

    private final Chat chat;
    private final ParametersRepository parametersRepository;
    private final ChatLogManager chatLogManager;

    @Value("${chat.api.max-request-length}")
    private Integer maxRequestLength;

    public ChatController(Chat chat, ParametersRepository parametersRepository, ChatLogManager chatLogManager) {
        this.chat = chat;
        this.parametersRepository = parametersRepository;
        this.chatLogManager = chatLogManager;
    }

    @PostMapping("/chat")
    public ResponseEntity<Response> chat(@RequestBody Request request) {
        validateRequest(request);

        Parameters parameters = parametersRepository.loadActive(ParametersTargetType.CHAT);
        Chat.StructuredResponse chatResponse = chat.requestStructured(
                request.text(), parameters.getContent(), request.conversationId(), null);

        chatLogManager.saveResponse(request.conversationId(), chatResponse);

        return ResponseEntity.ok(new Response(
                request.text(),
                chatResponse.text(),
                "",
                chatResponse.sourceLinks() != null ? chatResponse.sourceLinks() : List.of()
        ));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamEvent> chatStream(@RequestBody Request request) {
        validateRequest(request);

        return Flux.defer(() -> {
            Parameters parameters = parametersRepository.loadActive(ParametersTargetType.CHAT);
            return chat.requestStream(request.text(), parameters.getContent(), request.conversationId());
        });
    }

    private void validateRequest(Request request) {
        if (request.text == null || request.text.isBlank()) {
            chatLogManager.saveError(request.conversationId(), REQUEST_TEXT_IS_EMPTY_OR_BLANK);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, REQUEST_TEXT_IS_EMPTY_OR_BLANK);
        }
        if (request.text.length() > maxRequestLength) {
            chatLogManager.saveError(request.conversationId(), REQUEST_TEXT_IS_TOO_LONG);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, REQUEST_TEXT_IS_TOO_LONG);
        }
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
