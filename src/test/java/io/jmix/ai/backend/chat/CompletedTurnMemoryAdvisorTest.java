package io.jmix.ai.backend.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static io.jmix.ai.backend.chat.ScriptedChatModel.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class CompletedTurnMemoryAdvisorTest {

    private static final String SYSTEM_PROMPT = "# Role and Objective";
    private static final String CONVERSATION_ID = "conv-1";
    private static final String QUESTION_A = "How to use RichCodeEditor?";
    private static final String QUESTION_B = "How to display Polygon in Map?";
    private static final String QUESTION_C = "RichCodeEditor";
    private static final String ANSWER_A = "answer A";

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(10).build();
    private final ChatClient client = ChatClient.builder(model)
            .defaultAdvisors(new CompletedTurnMemoryAdvisor(memory))
            .build();

    @Test
    void call_recordsQuestionAndAnswerAndSendsHistoryAfterSystemPrompt() {
        model.answers(ANSWER_A).answers("answer C");

        call(QUESTION_A);
        call(QUESTION_C);

        assertMemory(QUESTION_A, ANSWER_A, QUESTION_C, "answer C");
        assertPrompt(model.prompts.get(1));
    }

    @Test
    void call_failedTurnIsNotRecordedAndNextTurnAnswersCurrentQuestion() {
        model.answers(ANSWER_A).fails().answers("answer C");
        call(QUESTION_A);

        assertThatThrownBy(() -> call(QUESTION_B)).hasStackTraceContaining("model failed");

        assertMemory(QUESTION_A, ANSWER_A);
        call(QUESTION_C);
        assertPrompt(model.prompts.get(2));
    }

    @Test
    void call_blankAnswerIsNotRecorded() {
        model.answers(ANSWER_A).answers(" ");
        call(QUESTION_A);

        call(QUESTION_B);

        assertMemory(QUESTION_A, ANSWER_A);
    }

    @Test
    void stream_recordsAggregatedAnswerOnCompletion() {
        model.streams(() -> Flux.just(response("Component "), response("richTextEditor")));

        stream(QUESTION_A).blockLast();

        assertMemory(QUESTION_A, "Component richTextEditor");
    }

    @Test
    void stream_failedTurnIsNotRecorded() {
        model.answers(ANSWER_A)
                .streams(() -> Flux.concat(Flux.just(response("partial")),
                        Flux.error(new IllegalStateException("model failed"))))
                .answers("answer C");
        call(QUESTION_A);

        assertThatThrownBy(() -> stream(QUESTION_B).blockLast()).hasStackTraceContaining("model failed");

        assertMemory(QUESTION_A, ANSWER_A);
        stream(QUESTION_C).blockLast();
        assertPrompt(model.prompts.get(2));
    }

    @Test
    void stream_cancelledTurnIsNotRecorded() {
        model.answers(ANSWER_A)
                .streams(() -> Flux.concat(Flux.just(response("partial")), Flux.never()))
                .answers("answer C");
        call(QUESTION_A);

        // the client abandons the stream after the first chunk
        List<ChatResponse> received = stream(QUESTION_B).take(1).collectList().block();

        assertThat(received).hasSize(1);
        assertMemory(QUESTION_A, ANSWER_A);
        stream(QUESTION_C).blockLast();
        assertPrompt(model.prompts.get(2));
    }

    private void call(String question) {
        client.prompt(prompt(question))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .chatResponse();
    }

    private Flux<ChatResponse> stream(String question) {
        return client.prompt(prompt(question))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .stream()
                .chatResponse();
    }

    private static Prompt prompt(String question) {
        return new Prompt(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(question)));
    }

    private void assertMemory(String... alternatingQuestionsAndAnswers) {
        List<Message> remembered = memory.get(CONVERSATION_ID);
        assertThat(remembered).extracting(Message::getText).containsExactly(alternatingQuestionsAndAnswers);
        for (int i = 0; i < remembered.size(); i++) {
            assertThat(remembered.get(i).getMessageType())
                    .isEqualTo(i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT);
        }
    }

    /** The turn for question C must see turn A as its only history, after the system prompt. */
    private static void assertPrompt(Prompt prompt) {
        assertThat(prompt.getInstructions())
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(MessageType.SYSTEM, SYSTEM_PROMPT),
                        tuple(MessageType.USER, QUESTION_A),
                        tuple(MessageType.ASSISTANT, ANSWER_A),
                        tuple(MessageType.USER, QUESTION_C));
    }
}
