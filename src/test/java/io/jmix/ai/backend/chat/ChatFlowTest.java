package io.jmix.ai.backend.chat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jmix.ai.backend.entity.ChatLog;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.retrieval.Reranker;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import test_support.chat.FakeToolCallingChatModel;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "pgvector.liquibase.enabled=false",
        "pgvector.datasource.url=jdbc:hsqldb:mem:pgvector",
        "pgvector.datasource.username=sa",
        "pgvector.datasource.password="
})
@ActiveProfiles("test")
class ChatFlowTest {

    private static final String QUESTION = "How to show a notification?";
    private static final String DOCS_TOOL = "documentation_retriever";
    private static final String SAMPLES_TOOL = "uisamples_retriever";
    private static final String DOCS_QUERY = "notifications";
    private static final String SAMPLES_QUERY = "notification samples";
    private static final String ANSWER_START = "Use the ";
    private static final String ANSWER_END = "Notifications bean.";
    private static final String NOTIFICATIONS_URL = "https://docs.jmix.io/notifications.html";
    private static final String VIEWS_URL = "https://docs.jmix.io/views.html";
    private static final String SAMPLE_URL = "https://demo.jmix.io/ui-samples/notification-simple";
    private static final double RERANK_SCORE = 0.9;
    private static final int PROMPT_TOKENS = 400;
    private static final int COMPLETION_TOKENS = 30;
    private static final String PARAMETERS = """
            systemMessage: You answer questions about Jmix.
            tools:
              documentation_retriever:
                description: Searches the Jmix documentation.
                similarityThreshold: 0.0
                topReranked: 2
                minScore: 0.0
                minRerankedScore: 0.0
              uisamples_retriever:
                description: Searches the UI samples.
                similarityThreshold: 0.0
                topReranked: 2
                minScore: 0.0
                minRerankedScore: 0.0
              trainings_retriever:
                enabled: false
            """;

    private final FakeToolCallingChatModel chatModel = new FakeToolCallingChatModel(
            List.of(
                    toolCall("call-1", DOCS_TOOL, DOCS_QUERY),
                    toolCall("call-2", SAMPLES_TOOL, SAMPLES_QUERY)),
            List.of(ANSWER_START, ANSWER_END),
            new DefaultUsage(100, 10),
            new DefaultUsage(300, 20));

    @MockitoBean
    private VectorStore vectorStore;
    @MockitoBean
    private VectorStoreRepository vectorStoreRepository;
    @MockitoBean
    private Reranker reranker;
    @MockitoSpyBean
    private ChatImpl chat;
    @Autowired
    private UnconstrainedDataManager dataManager;

    @TestConfiguration
    static class InMemoryChatMemory {

        @Bean
        @Primary
        ChatMemoryRepository inMemoryChatMemoryRepository() {
            return new InMemoryChatMemoryRepository();
        }
    }

    private final Logger chatLogLogger = (Logger) LoggerFactory.getLogger(ChatLogAdvisor.class);
    private final ListAppender<ILoggingEvent> console = new ListAppender<>();

    @BeforeEach
    void setUp() {
        console.start();
        chatLogLogger.addAppender(console);
        chatLogLogger.setLevel(Level.DEBUG);
        doReturn(chatModel).when(chat).buildChatModel(any());
        givenTheVectorStoreFindsPagesForEveryTool();
        givenTheRerankerKeepsEveryCandidate();
    }

    @AfterEach
    void tearDown() {
        chatLogLogger.detachAppender(console);
        chatLogLogger.setLevel(null);
    }

    @Test
    void aStreamedTurnLooksTheSameInTheStreamTheConsoleAndTheChatLog() {
        // Act
        List<EventStreamValueHolder> stream = chat.requestStream(QUESTION, PARAMETERS, "streamed-turn", JmixVersion.V2)
                .map(StreamingEvent::value)
                .collectList()
                .block();

        // Assert
        ChatLog chatLog = awaitChatLogOf("streamed-turn");
        List<String> consoleLines = console.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        List<String> chatLogLines = Arrays.asList(chatLog.getContent().split("\n"));

        assertThat(stream)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "RequestInfo",
                        "ToolCallStart", "ToolRetrieved", "ToolReranked", "ToolCallEnd",
                        "ToolCallStart", "ToolRetrieved", "ToolReranked", "ToolCallEnd",
                        "TokensStart", "Content", "Content", "TokensEnd",
                        "SourcesStart", "Metadata", "Metadata", "Metadata",
                        "RequestEnd");
        assertThat(consoleLines)
                .extracting(ChatFlowTest::shape)
                .containsExactly(
                        "Model: <options>, User prompt: " + QUESTION,
                        ">>> Using documentation_retriever: notifications",
                        "Found documents (2): <documents>",
                        "Reranked documents (2): <documents>",
                        "documentation_retriever done in N ms",
                        ">>> Using uisamples_retriever: notification samples",
                        "Found documents (1): <documents>",
                        "Reranked documents (1): <documents>",
                        "uisamples_retriever done in N ms",
                        "Received response in N ms [promptTokens: 400, completionTokens: 30]");
        assertThat(chatLogLines)
                .extracting(ChatFlowTest::shape)
                .containsExactly(
                        "T Model: <options>, User prompt: " + QUESTION,
                        "T >>> Using documentation_retriever: notifications",
                        "T Found documents (2) in N ms: <documents>",
                        "T Reranked documents (2) in N ms: <documents>",
                        "T documentation_retriever done in N ms",
                        "T >>> Using uisamples_retriever: notification samples",
                        "T Found documents (1) in N ms: <documents>",
                        "T Reranked documents (1) in N ms: <documents>",
                        "T uisamples_retriever done in N ms",
                        "T Received response in N ms [promptTokens: 400, completionTokens: 30]");

        assertThat(eventsOf(stream, EventStreamValueHolder.ToolCallStart.class))
                .extracting(EventStreamValueHolder.ToolCallStart::tool)
                .containsExactly(DOCS_TOOL, SAMPLES_TOOL)
                .isEqualTo(captured(consoleLines, ">>> Using (\\w+):"))
                .isEqualTo(captured(chatLogLines, ">>> Using (\\w+):"));
        assertThat(eventsOf(stream, EventStreamValueHolder.ToolRetrieved.class))
                .extracting(retrieved -> ChatLogFormatter.formatDocScores(retrieved.documents()))
                .isEqualTo(captured(consoleLines, "^Found documents \\(\\d+\\): (.*)$"))
                .isEqualTo(captured(chatLogLines, "Found documents \\(\\d+\\) in \\d+ ms: (.*)$"));
        assertThat(eventsOf(stream, EventStreamValueHolder.ToolReranked.class))
                .extracting(reranked -> ChatLogFormatter.formatDocScores(reranked.documents()))
                .isEqualTo(captured(consoleLines, "^Reranked documents \\(\\d+\\): (.*)$"))
                .isEqualTo(captured(chatLogLines, "Reranked documents \\(\\d+\\) in \\d+ ms: (.*)$"));
        assertThat(eventsOf(stream, EventStreamValueHolder.ToolCallEnd.class))
                .extracting(end -> String.valueOf(end.totalDurationMs()))
                .isEqualTo(captured(consoleLines, " done in (\\d+) ms"))
                .isEqualTo(captured(chatLogLines, " done in (\\d+) ms"));
        assertThat(eventsOf(stream, EventStreamValueHolder.RequestEnd.class))
                .singleElement()
                .extracting(EventStreamValueHolder.RequestEnd::promptTokens, EventStreamValueHolder.RequestEnd::completionTokens)
                .containsExactly(chatLog.getPromptTokens(), chatLog.getCompletionTokens())
                .containsExactly(PROMPT_TOKENS, COMPLETION_TOKENS);
        assertThat(eventsOf(stream, EventStreamValueHolder.Metadata.class))
                .extracting(EventStreamValueHolder.Metadata::source)
                .containsExactly(NOTIFICATIONS_URL, VIEWS_URL, SAMPLE_URL)
                .isEqualTo(List.of(chatLog.getSources().split(",")));
    }

    @Test
    void aCalledTurnLooksTheSameInTheResponseTheChatLogAndTheCheckRunLog() {
        // Act
        Chat.StructuredResponse response = chat.requestStructured(QUESTION, PARAMETERS, "called-turn", JmixVersion.V2, true);

        // Assert
        ChatLog chatLog = chatLogOf("called-turn");
        List<String> chatLogLines = Arrays.asList(chatLog.getContent().split("\n"));

        assertThat(chatLogLines)
                .extracting(ChatFlowTest::shape)
                .containsExactly(
                        "T Model: <options>, User prompt: " + QUESTION,
                        "T >>> Using documentation_retriever: notifications",
                        "T Found documents (2): <documents>",
                        "T Reranked documents (2): <documents>",
                        "T documentation_retriever done in N ms",
                        "T >>> Using uisamples_retriever: notification samples",
                        "T Found documents (1): <documents>",
                        "T Reranked documents (1): <documents>",
                        "T uisamples_retriever done in N ms",
                        "T Received response in N ms [promptTokens: 400, completionTokens: 30]:",
                        ANSWER_START + ANSWER_END);
        assertThat(response.retrievalLog())
                .isEqualTo(chatLogLines.subList(1, 9)
                        .stream()
                        .map(line -> line.substring("HH:mm:ss ".length()))
                        .toList());
        assertThat(List.of(response.text(), response.promptTokens(), response.completionTokens(), String.join(",", response.sourceLinks())))
                .containsExactly(ANSWER_START + ANSWER_END, chatLog.getPromptTokens(), chatLog.getCompletionTokens(), chatLog.getSources());
        assertThat(List.of(chatLog.getPromptTokens(), chatLog.getCompletionTokens()))
                .containsExactly(PROMPT_TOKENS, COMPLETION_TOKENS);
    }

    private void givenTheVectorStoreFindsPagesForEveryTool() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            return DOCS_QUERY.equals(request.getQuery())
                    ? List.of(document("notifications", NOTIFICATIONS_URL, 0.7), document("views", VIEWS_URL, 0.6))
                    : List.of(document("sample", SAMPLE_URL, 0.8));
        });
    }

    private void givenTheRerankerKeepsEveryCandidate() {
        when(reranker.rerank(any(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            List<Document> candidates = invocation.getArgument(1);
            return candidates.stream()
                    .map(candidate -> new Reranker.Result(candidate, RERANK_SCORE))
                    .toList();
        });
    }

    private ChatLog chatLogOf(String conversationId) {
        return dataManager.load(ChatLog.class)
                .query("e.conversationId = :conversationId")
                .parameter("conversationId", conversationId)
                .one();
    }

    private ChatLog awaitChatLogOf(String conversationId) {
        return await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> dataManager.load(ChatLog.class)
                        .query("e.conversationId = :conversationId")
                        .parameter("conversationId", conversationId)
                        .optional(), Optional::isPresent)
                .orElseThrow();
    }

    private static AssistantMessage.ToolCall toolCall(String id, String tool, String query) {
        return new AssistantMessage.ToolCall(id, "function", tool, "{\"queryText\": \"%s\"}".formatted(query));
    }

    private static Document document(String id, String url, double score) {
        return Document.builder()
                .id(id)
                .text(id)
                .metadata(Map.of("url", url, "source", url))
                .score(score)
                .build();
    }

    private static <T> List<T> eventsOf(List<EventStreamValueHolder> stream, Class<T> type) {
        return stream.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static List<String> captured(List<String> lines, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return lines.stream()
                .map(pattern::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .toList();
    }

    private static String shape(String line) {
        return line.replaceAll("^\\d\\d:\\d\\d:\\d\\d ", "T ")
                .replaceAll("Model: .*?, User prompt: ", "Model: <options>, User prompt: ")
                .replaceAll("\\d+ ms", "N ms")
                .replaceAll(": \\[\\(.*]$", ": <documents>");
    }
}
