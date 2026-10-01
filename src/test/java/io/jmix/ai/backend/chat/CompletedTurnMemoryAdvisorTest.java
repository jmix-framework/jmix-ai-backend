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
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.scheduler.Schedulers;
import test_support.ai.ScriptedChatModel;
import test_support.ai.TestConversation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static test_support.ai.ChatMessages.assistant;
import static test_support.ai.ChatMessages.describe;
import static test_support.ai.ChatMessages.system;
import static test_support.ai.ChatMessages.user;

/**
 * Drives the chat client that {@link ChatImpl} builds: a scripted model, a real conversation memory.
 * A turn is "remembered" when its question and answer are in the memory, and the memory is what the
 * model receives as history on the next turn.
 */
@ExtendWith(MockitoExtension.class)
class CompletedTurnMemoryAdvisorTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String SYSTEM_PROMPT = "You answer questions about Jmix.";

    private static final String FIRST_QUESTION = "How to use RichCodeEditor?";
    private static final String FIRST_ANSWER = "Add a richCodeEditor component to the view.";
    private static final String INTERRUPTED_QUESTION = "How to display Polygon in Map?";
    private static final String NEXT_QUESTION = "RichCodeEditor";
    private static final String NEXT_ANSWER = "RichCodeEditor edits source code.";

    @Mock
    private ParametersRepository parametersRepository;
    @Mock
    private ToolsManager toolsManager;
    @Mock
    private ChatLogManager chatLogManager;
    @Mock
    private SystemPromptResolver systemPromptResolver;

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final ChatMemoryRepository memory = new InMemoryChatMemoryRepository();
    private TestConversation conversation;

    @BeforeEach
    void setUp() {
        ChatImpl chat = new ChatImpl(
                memory,
                parametersRepository,
                Schedulers.immediate(),
                toolsManager,
                chatLogManager,
                systemPromptResolver);
        ChatClient client = chat.buildClient(model);
        conversation = new TestConversation(
                client,
                CONVERSATION_ID,
                question -> chat.buildPrompt(question, SYSTEM_PROMPT));
    }

    @Test
    void remembersAnAnsweredTurn() {
        model.repliesWith(FIRST_ANSWER);

        conversation.ask(FIRST_QUESTION);

        assertThat(rememberedMessages()).containsExactly(
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER));
    }

    @Test
    void sendsTheHistoryAfterTheSystemPrompt() {
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION);

        model.repliesWith(NEXT_ANSWER);
        conversation.ask(NEXT_QUESTION);

        assertThat(describe(model.lastPrompt())).containsExactly(
                system(SYSTEM_PROMPT),
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER),
                user(NEXT_QUESTION));
    }

    /** The options carry the tool callbacks: losing them would silently switch retrieval off. */
    @Test
    void keepsTheRequestOptionsOnAFollowUpTurn() {
        ChatOptions options = ChatOptions.builder()
                .model("test-model")
                .build();
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION, options);

        model.repliesWith(NEXT_ANSWER);
        conversation.ask(NEXT_QUESTION, options);

        ChatOptions receivedOptions = model.lastPrompt().getOptions();
        assertThat(receivedOptions).extracting(ChatOptions::getModel).isEqualTo("test-model");
    }

    @Test
    void forgetsATurnWhoseCallFailed() {
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION);

        model.fails();
        assertThatThrownBy(() -> conversation.ask(INTERRUPTED_QUESTION))
                .hasStackTraceContaining(ScriptedChatModel.FAILURE_MESSAGE);

        assertThat(rememberedMessages()).containsExactly(
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER));
    }

    /** The scenario from issue #25: the next question must not be sent after an unanswered one. */
    @Test
    void sendsTheNextQuestionWithoutTheOneWhoseCallFailed() {
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION);
        model.fails();
        assertThatThrownBy(() -> conversation.ask(INTERRUPTED_QUESTION))
                .hasStackTraceContaining(ScriptedChatModel.FAILURE_MESSAGE);

        model.repliesWith(NEXT_ANSWER);
        conversation.ask(NEXT_QUESTION);

        assertThat(describe(model.lastPrompt())).containsExactly(
                system(SYSTEM_PROMPT),
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER),
                user(NEXT_QUESTION));
    }

    @Test
    void forgetsATurnWithABlankAnswer() {
        model.repliesWith(" ");

        conversation.ask(FIRST_QUESTION);

        assertThat(rememberedMessages()).isEmpty();
    }

    @Test
    void remembersAStreamedAnswerAsOneMessage() {
        model.streamsChunks("Add a richCodeEditor ", "component to the view.");

        conversation.askStreaming(FIRST_QUESTION);

        assertThat(rememberedMessages()).containsExactly(
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER));
    }

    @Test
    void forgetsAStreamThatFailedMidway() {
        model.streamsChunkThenFails("Use the ");

        assertThatThrownBy(() -> conversation.askStreaming(INTERRUPTED_QUESTION))
                .hasStackTraceContaining(ScriptedChatModel.FAILURE_MESSAGE);

        assertThat(rememberedMessages()).isEmpty();
    }

    @Test
    void forgetsAStreamTheClientAbandoned() {
        model.streamsChunkThenHangs("Use the ");

        String receivedBeforeAbandoning = conversation.askStreamingAndAbandonAfterFirstChunk(INTERRUPTED_QUESTION);

        assertThat(receivedBeforeAbandoning).isEqualTo("Use the ");
        assertThat(rememberedMessages()).isEmpty();
    }

    private List<String> rememberedMessages() {
        return describe(memory.findByConversationId(CONVERSATION_ID));
    }
}
