package io.jmix.ai.backend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chat.ChatImpl;
import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ChatController {

    private static final String REQUEST_TEXT_IS_EMPTY_OR_BLANK = "Request text is empty or blank";
    private static final String REQUEST_TEXT_IS_TOO_LONG = "Request text is too long";

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Chat chat;
    private final ParametersRepository parametersRepository;
    private final ChatLogManager chatLogManager;

    @Value( "${chat.api.max-request-length}")
    private Integer maxRequestLength;

    public ChatController(Chat chat, ParametersRepository parametersRepository, ChatLogManager chatLogManager) {
        this.chat = chat;
        this.parametersRepository = parametersRepository;
        this.chatLogManager = chatLogManager;
    }

    @PostMapping("/chat")
    public ResponseEntity<Response> chat(@RequestBody Request request) {
        if (request.text == null || request.text.isBlank()) {
            log.debug(REQUEST_TEXT_IS_EMPTY_OR_BLANK);
            chatLogManager.saveError(request.conversationId(), REQUEST_TEXT_IS_EMPTY_OR_BLANK);
            return ResponseEntity.badRequest().build();
        }
        if (request.text.length() > maxRequestLength) {
            log.debug(REQUEST_TEXT_IS_TOO_LONG);
            chatLogManager.saveError(request.conversationId(), REQUEST_TEXT_IS_TOO_LONG);
            return ResponseEntity.badRequest().build();
        }
        Parameters parameters = parametersRepository.loadActive(ParametersTargetType.CHAT);
        ChatImpl.StructuredResponse chatResponse = chat.requestStructured(
                request.text(), parameters.getContent(), request.conversationId(), null);

        chatLogManager.saveResponse(request.conversationId(), chatResponse);

        return ResponseEntity.ok(new Response(
                request.text(),
                chatResponse.text(),
                "",
                chatResponse.sourceLinks() != null ? chatResponse.sourceLinks() : List.of()
        ));
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
