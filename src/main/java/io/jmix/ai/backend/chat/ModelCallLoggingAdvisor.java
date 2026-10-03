package io.jmix.ai.backend.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

public class ModelCallLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLoggingAdvisor.class);

    private final int order;

    public ModelCallLoggingAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        log.trace("LLM Request:\n{}", chatClientRequest.prompt());

        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);

        log.trace("LLM Response:\n{}", response.chatResponse());
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        if (!log.isTraceEnabled()) {
            return streamAdvisorChain.nextStream(chatClientRequest);
        }
        return Flux.defer(() -> {
            log.trace("LLM Request:\n{}", chatClientRequest.prompt());
            return new ChatClientMessageAggregator().aggregateChatClientResponse(
                    streamAdvisorChain.nextStream(chatClientRequest),
                    wholeResponse -> log.trace("LLM Response:\n{}", wholeResponse.chatResponse()));
        });
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }
}
