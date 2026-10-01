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
import static org.assertj.core.api.Assertions.catchThrowable;
import static test_support.ai.ChatMessages.assistant;
import static test_support.ai.ChatMessages.describe;
import static test_support.ai.ChatMessages.system;
import static test_support.ai.ChatMessages.user;

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
        // Arrange
        model.repliesWith(FIRST_ANSWER);

        // Act
        conversation.ask(FIRST_QUESTION);

        // Assert
        assertThat(rememberedMessages()).containsExactly(
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER));
    }

    @Test
    void sendsTheHistoryAfterTheSystemPrompt() {
        // Arrange
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION);
        model.repliesWith(NEXT_ANSWER);

        // Act
        conversation.ask(NEXT_QUESTION);

        // Assert
        assertThat(describe(model.lastPrompt())).containsExactly(
                system(SYSTEM_PROMPT),
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER),
                user(NEXT_QUESTION));
    }

    @Test
    void keepsTheRequestOptionsOnAFollowUpTurn() {
        // Arrange
        ChatOptions options = ChatOptions.builder()
                .model("test-model")
                .build();
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION, options);
        model.repliesWith(NEXT_ANSWER);

        // Act
        conversation.ask(NEXT_QUESTION, options);

        // Assert
        ChatOptions receivedOptions = model.lastPrompt().getOptions();
        assertThat(receivedOptions).extracting(ChatOptions::getModel).isEqualTo("test-model");
    }

    @Test
    void forgetsATurnWhoseCallFailed() {
        // Arrange
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION);
        model.fails();

        // Act
        Throwable failure = catchThrowable(() -> conversation.ask(INTERRUPTED_QUESTION));

        // Assert
        assertThat(failure).hasStackTraceContaining(ScriptedChatModel.FAILURE_MESSAGE);
        assertThat(rememberedMessages()).containsExactly(
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER));
    }

    @Test
    void sendsTheNextQuestionWithoutTheOneWhoseCallFailed() {
        // Arrange
        model.repliesWith(FIRST_ANSWER);
        conversation.ask(FIRST_QUESTION);
        model.fails();
        conversation.askAndExpectFailure(INTERRUPTED_QUESTION);
        model.repliesWith(NEXT_ANSWER);

        // Act
        conversation.ask(NEXT_QUESTION);

        // Assert
        assertThat(describe(model.lastPrompt())).containsExactly(
                system(SYSTEM_PROMPT),
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER),
                user(NEXT_QUESTION));
    }

    @Test
    void forgetsATurnWithABlankAnswer() {
        // Arrange
        model.repliesWith(" ");

        // Act
        conversation.ask(FIRST_QUESTION);

        // Assert
        assertThat(rememberedMessages()).isEmpty();
    }

    @Test
    void remembersAStreamedAnswerAsOneMessage() {
        // Arrange
        model.streamsChunks("Add a richCodeEditor ", "component to the view.");

        // Act
        conversation.askStreaming(FIRST_QUESTION);

        // Assert
        assertThat(rememberedMessages()).containsExactly(
                user(FIRST_QUESTION),
                assistant(FIRST_ANSWER));
    }

    @Test
    void forgetsAStreamThatFailedMidway() {
        // Arrange
        model.streamsChunkThenFails("Use the ");

        // Act
        Throwable failure = catchThrowable(() -> conversation.askStreaming(INTERRUPTED_QUESTION));

        // Assert
        assertThat(failure).hasStackTraceContaining(ScriptedChatModel.FAILURE_MESSAGE);
        assertThat(rememberedMessages()).isEmpty();
    }

    @Test
    void forgetsAStreamTheClientAbandoned() {
        // Arrange
        model.streamsChunkThenHangs("Use the ");

        // Act
        String receivedBeforeAbandoning = conversation.askStreamingAndAbandonAfterFirstChunk(INTERRUPTED_QUESTION);

        // Assert
        assertThat(receivedBeforeAbandoning).isEqualTo("Use the ");
        assertThat(rememberedMessages()).isEmpty();
    }

    private List<String> rememberedMessages() {
        return describe(memory.findByConversationId(CONVERSATION_ID));
    }
}
