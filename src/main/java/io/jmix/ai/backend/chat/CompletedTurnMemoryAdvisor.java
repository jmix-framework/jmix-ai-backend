package io.jmix.ai.backend.chat;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Conversation memory that records a turn only once the model has answered it.
 * <p>
 * Replaces {@code MessageChatMemoryAdvisor}, which writes the user message to memory before the
 * model call and the answer after it: a failed or interrupted generation (model error, backend
 * restart, abandoned stream) left the question in memory without an answer, and the next turn
 * replayed it to the model, which then often answered it instead of the current question.
 * <p>
 * Here the question and the answer are written together, in one memory update, after a call
 * returns or a stream completes. An error, a cancelled stream or an empty answer writes nothing,
 * so the turn is dropped from the model context. The clients keep their own copy of what the
 * user saw.
 * <p>
 * The history is placed after the system messages of the prompt, so the system prompt stays
 * the first message on every turn.
 */
public class CompletedTurnMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatMemory chatMemory;

    public CompletedTurnMemoryAdvisor(ChatMemory chatMemory) {
        this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        String conversationId = getConversationId(chatClientRequest);
        ChatClientRequest requestWithHistory = withHistory(chatClientRequest, conversationId);

        // a failed model call throws here, before the turn is remembered
        ChatClientResponse response = callAdvisorChain.nextCall(requestWithHistory);

        ChatResponse answer = response.chatResponse();
        rememberTurn(conversationId, chatClientRequest, answer);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        // deferred, so that the history is read when the stream is subscribed, not when it is assembled
        return Flux.defer(() -> {
            String conversationId = getConversationId(chatClientRequest);
            ChatClientRequest requestWithHistory = withHistory(chatClientRequest, conversationId);

            Flux<ChatClientResponse> responses = streamAdvisorChain.nextStream(requestWithHistory);

            // the aggregator calls back on completion only: an error or a cancellation skips it
            ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();
            return aggregator.aggregateChatClientResponse(responses, wholeStreamedResponse -> {
                ChatResponse answer = wholeStreamedResponse.chatResponse();
                rememberTurn(conversationId, chatClientRequest, answer);
            });
        });
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    private static String getConversationId(ChatClientRequest request) {
        Object conversationId = request.context().get(ChatMemory.CONVERSATION_ID);
        return conversationId != null ? conversationId.toString() : ChatMemory.DEFAULT_CONVERSATION_ID;
    }

    private ChatClientRequest withHistory(ChatClientRequest request, String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        if (history.isEmpty()) {
            return request;
        }
        Prompt prompt = request.prompt();
        List<Message> instructions = prompt.getInstructions();
        List<Message> systemMessages = instructions.stream()
                .filter(SystemMessage.class::isInstance)
                .toList();
        List<Message> currentTurnMessages = instructions.stream()
                .filter(message -> !(message instanceof SystemMessage))
                .toList();

        List<Message> messages = new ArrayList<>(instructions.size() + history.size());
        messages.addAll(systemMessages);
        messages.addAll(history);
        messages.addAll(currentTurnMessages);

        // the options carry the tool callbacks, so the new prompt must keep them
        Prompt promptWithHistory = new Prompt(messages, prompt.getOptions());
        return request.mutate()
                .prompt(promptWithHistory)
                .build();
    }

    private void rememberTurn(String conversationId,
                              ChatClientRequest request,
                              @Nullable ChatResponse chatResponse) {
        UserMessage question = request.prompt().getUserMessage();
        if (StringUtils.isBlank(question.getText()) || chatResponse == null) {
            return;
        }
        List<Message> answers = chatResponse.getResults()
                .stream()
                .map(Generation::getOutput)
                .filter(output -> StringUtils.isNotBlank(output.getText()))
                .map(Message.class::cast)
                .toList();
        if (answers.isEmpty()) {
            // a question without an answer is exactly what must not get into memory
            return;
        }
        List<Message> turn = new ArrayList<>(answers.size() + 1);
        turn.add(question);
        turn.addAll(answers);
        chatMemory.add(conversationId, turn);
    }
}
