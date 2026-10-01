package io.jmix.ai.backend.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves the system messages to the head of the prompt, keeping the relative order of all messages.
 * <p>
 * {@code MessageChatMemoryAdvisor} prepends the conversation history to the whole prompt, so from the
 * second turn on the model would receive the previous turns before its instructions. A leading system
 * message is the contract models are trained on, and it is the stable prefix that provider-side prompt
 * caching reuses across turns.
 * <p>
 * Runs right before the model call, after every advisor that may inject messages.
 */
public class SystemMessageFirstAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        return callAdvisorChain.nextCall(reorder(chatClientRequest));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        return streamAdvisorChain.nextStream(reorder(chatClientRequest));
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // the model call advisors are at LOWEST_PRECEDENCE
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    static ChatClientRequest reorder(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        List<Message> reordered = new ArrayList<>(messages.size());
        messages.stream().filter(SystemMessage.class::isInstance).forEach(reordered::add);
        messages.stream().filter(message -> !(message instanceof SystemMessage)).forEach(reordered::add);
        if (reordered.equals(messages)) {
            return request;
        }
        return request.mutate()
                .prompt(request.prompt().mutate().messages(reordered).build())
                .build();
    }
}
