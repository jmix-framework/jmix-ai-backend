package io.jmix.ai.backend.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

public class RoundUsageAdvisor implements CallAdvisor, StreamAdvisor {

    private final int order;

    public RoundUsageAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);

        TurnTrace trace = TurnTrace.from(chatClientRequest.context());
        if (trace != null && response.chatResponse() != null) {
            trace.addUsage(response.chatResponse().getMetadata().getUsage());
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        TurnTrace trace = TurnTrace.from(chatClientRequest.context());
        if (trace == null) {
            return streamAdvisorChain.nextStream(chatClientRequest);
        }
        return Flux.defer(() -> {
            AtomicReference<Usage> roundUsage = new AtomicReference<>();
            return streamAdvisorChain.nextStream(chatClientRequest)
                    .doOnNext(response -> rememberUsage(response.chatResponse(), roundUsage))
                    .doOnComplete(() -> trace.addUsage(roundUsage.get()));
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

    private static void rememberUsage(ChatResponse chatResponse, AtomicReference<Usage> roundUsage) {
        if (chatResponse == null) {
            return;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            roundUsage.set(usage);
        }
    }
}
