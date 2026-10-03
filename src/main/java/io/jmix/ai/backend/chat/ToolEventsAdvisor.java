package io.jmix.ai.backend.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

public class ToolEventsAdvisor implements StreamAdvisor {

    public static final String EVENT = "jmix.toolEvent";

    private final int order;

    public ToolEventsAdvisor(int order) {
        this.order = order;
    }

    @Nullable
    public static EventStreamValueHolder eventOf(@Nullable ChatResponse chatResponse) {
        return chatResponse != null ? chatResponse.getMetadata().get(EVENT) : null;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        TurnTrace trace = TurnTrace.from(chatClientRequest.context());
        if (trace == null) {
            return streamAdvisorChain.nextStream(chatClientRequest);
        }
        Flux<ChatClientResponse> toolEvents = trace.toolEvents()
                .map(event -> eventResponse(chatClientRequest, event));
        Flux<ChatClientResponse> modelResponses = streamAdvisorChain.nextStream(chatClientRequest)
                .doFinally(ignored -> trace.completeToolEvents());
        return Flux.merge(toolEvents, modelResponses);
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    private static ChatClientResponse eventResponse(ChatClientRequest request, EventStreamValueHolder event) {
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(""))))
                .metadata(ChatResponseMetadata.builder()
                        .keyValue(EVENT, event)
                        .build())
                .build();
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }
}
