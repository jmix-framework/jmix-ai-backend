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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.scheduler.Schedulers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.USER;

@ExtendWith(MockitoExtension.class)
class ChatImplTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String SYSTEM_PROMPT = "You answer questions about Jmix.";

    private static final String FIRST_QUESTION = "How to use RichCodeEditor?";
    private static final String FIRST_ANSWER = "Add a richCodeEditor component to the view.";
    private static final String INTERRUPTED_QUESTION = "How to display Polygon in Map?";
    private static final String MODEL_FAILURE = "model failed";

    @Mock
    private ParametersRepository parametersRepository;
    @Mock
    private ToolsManager toolsManager;
    @Mock
    private ChatLogManager chatLogManager;
    @Mock
    private SystemPromptResolver systemPromptResolver;

    private final ChatMemoryRepository memoryRepository = new InMemoryChatMemoryRepository();
    private ChatImpl chat;

    @BeforeEach
    void setUp() {
        chat = new ChatImpl(
                memoryRepository,
                parametersRepository,
                Schedulers.immediate(),
                toolsManager,
                chatLogManager,
                systemPromptResolver);
    }

    @Test
    void buildsAClientThatForgetsATurnWhoseCallFailed() {
        // Arrange
        memoryRepository.saveAll(CONVERSATION_ID, List.of(
                new UserMessage(FIRST_QUESTION),
                new AssistantMessage(FIRST_ANSWER)));
        ChatModel failingModel = ignored -> {
            throw new IllegalStateException(MODEL_FAILURE);
        };
        ChatClient client = chat.buildClient(failingModel);
        Prompt prompt = chat.buildPrompt(INTERRUPTED_QUESTION, SYSTEM_PROMPT);
        ChatClient.ChatClientRequestSpec request = client.prompt(prompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID));

        // Act
        Throwable failure = catchThrowable(() -> request.call()
                .chatResponse());

        // Assert
        assertThat(failure)
                .hasStackTraceContaining(MODEL_FAILURE);
        assertThat(memoryRepository.findByConversationId(CONVERSATION_ID))
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(USER, FIRST_QUESTION),
                        tuple(ASSISTANT, FIRST_ANSWER));
    }
}
