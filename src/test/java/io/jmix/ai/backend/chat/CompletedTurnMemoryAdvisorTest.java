package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.retrieval.ToolsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@ExtendWith(MockitoExtension.class)
class CompletedTurnMemoryAdvisorTest {

    private static final String SYSTEM_PROMPT = "# Role and Objective";
    private static final String CONVERSATION_ID = "conv-1";
    private static final String QUESTION_A = "How to use RichCodeEditor?";
    private static final String QUESTION_B = "How to display Polygon in Map?";
    private static final String QUESTION_C = "RichCodeEditor";
    private static final String ANSWER_A = "answer A";

    @Mock
    private ParametersRepository parametersRepository;
    @Mock
    private ToolsManager toolsManager;
    @Mock
    private ChatLogManager chatLogManager;
    @Mock
    private SystemPromptResolver systemPromptResolver;

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final ChatMemoryRepository memoryRepository = new InMemoryChatMemoryRepository();
    private ChatImpl chat;
    private ChatClient client;

    @BeforeEach
    void setUp() {
        chat = new ChatImpl(
                memoryRepository,
                parametersRepository,
                Schedulers.immediate(),
                toolsManager,
                chatLogManager,
                systemPromptResolver);
        client = chat.buildClient(model);
    }

    @Test
    void call_recordsQuestionAndAnswerAndSendsHistoryAfterSystemPrompt() {
        model.answers(ANSWER_A).answers("answer C");

        call(QUESTION_A);
        call(QUESTION_C);

        assertMemory(QUESTION_A, ANSWER_A, QUESTION_C, "answer C");
        assertPrompt(model.prompts.get(1));
    }

    @Test
    void call_followUpTurnKeepsRequestOptions() {
        model.answers(ANSWER_A).answers("answer C");
        ChatOptions options = ChatOptions.builder()
                .model("test-model")
                .build();

        callWithOptions(QUESTION_A, options);
        callWithOptions(QUESTION_C, options);

        assertPrompt(model.prompts.get(1));
        ChatOptions followUpOptions = model.prompts.get(1).getOptions();
        assertThat(followUpOptions).extracting(ChatOptions::getModel).isEqualTo("test-model");
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
        client.prompt(chat.buildPrompt(question, SYSTEM_PROMPT))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .chatResponse();
    }

    private void callWithOptions(String question, ChatOptions options) {
        client.prompt(chat.buildPrompt(question, SYSTEM_PROMPT))
                .options(options)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .chatResponse();
    }

    private Flux<ChatResponse> stream(String question) {
        return client.prompt(chat.buildPrompt(question, SYSTEM_PROMPT))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .stream()
                .chatResponse();
    }

    private void assertMemory(String... alternatingQuestionsAndAnswers) {
        List<Message> remembered = memoryRepository.findByConversationId(CONVERSATION_ID);
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

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** Plays back one scripted reply per model request, for both call() and stream(). */
    private static class ScriptedChatModel implements ChatModel {

        private final List<Prompt> prompts = new ArrayList<>();
        private final Deque<Supplier<Flux<ChatResponse>>> script = new ArrayDeque<>();

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
}
