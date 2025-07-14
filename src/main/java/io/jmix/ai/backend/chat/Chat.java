package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

import static org.apache.commons.lang3.StringUtils.abbreviate;

@Component
public class Chat {

    private static final Logger log = LoggerFactory.getLogger(Chat.class);

    private final InitialRetriever initialRetriever;
    private final VectorStore vectorStore;
    private final Reranker reranker;
    private final ParametersRepository parametersRepository;

    public record StructuredResponse(String text, List<String> logMessages,
                                     @Nullable List<Document> retrievedDocuments, @Nullable List<String> sourceLinks) {

        public StructuredResponse(String text, List<String> logMessages, @Nullable List<Document> retrievedDocuments) {
            this(text, logMessages, retrievedDocuments, getSourceLinks(retrievedDocuments));
        }

        private static List<String> getSourceLinks(@Nullable List<Document> retrievedDocuments) {
            if (retrievedDocuments == null) {
                return null;
            }
            return retrievedDocuments.stream()
                    .map(document -> document.getMetadata().get("url"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
    }

    public Chat(InitialRetriever initialRetriever, VectorStore vectorStore, Reranker reranker, ParametersRepository parametersRepository) {
        this.initialRetriever = initialRetriever;
        this.vectorStore = vectorStore;
        this.reranker = reranker;
        this.parametersRepository = parametersRepository;
    }

    public StructuredResponse requestStructured(String userPrompt, Parameters parameters, @Nullable Consumer<String> externalLogger) {
        long start = System.currentTimeMillis();
        List<String> logMessages = new ArrayList<>();

        ParametersReader parametersReader = parametersRepository.getReader(parameters);

        ChatModel chatModel = buildChatModel(parametersReader);
        addLogMessage(logMessages, "Model: %s, User prompt: %s".formatted(chatModel.getDefaultOptions(), abbreviate(userPrompt, 200)));

        ChatClient chatClient = buildClient(chatModel);

        List<Document> retrievedDocuments = new ArrayList<>();

        Consumer<String> internalLogger = message -> {
            if (externalLogger != null)
                externalLogger.accept(message);
            addLogMessage(logMessages, message);
        };

        ChatClient.ChatClientRequestSpec request;

        List<Document> documents = initialRetriever.retrieve(userPrompt, parametersReader, internalLogger);
        if (!documents.isEmpty()) {
            internalLogger.accept("Using RagAdvisor");
            retrievedDocuments.addAll(documents);

            request = chatClient.prompt(buildPrompt(userPrompt, parametersReader.getString("systemMessage.rag")));
            request.advisors(new RagAdvisor(documents));

        } else {
            internalLogger.accept("Using tools");
            DocsTool docsTool = new DocsTool(vectorStore, reranker, parametersReader, retrievedDocuments, internalLogger);
            UiSamplesTool uiSamplesTool = new UiSamplesTool(vectorStore, reranker, parametersReader, retrievedDocuments, internalLogger);
            TrainingsTool trainingsTool = new TrainingsTool(vectorStore, reranker, parametersReader, retrievedDocuments, internalLogger);

            request = chatClient.prompt(buildPrompt(userPrompt, parametersReader.getString("systemMessage.tools")));
            request.toolCallbacks(docsTool.getToolCallback(), uiSamplesTool.getToolCallback(), trainingsTool.getToolCallback());
        }

        ChatResponse chatResponse = request.call().chatResponse();

        List<Document> distinctDocuments = getDistinctDocuments(retrievedDocuments);

        if (chatResponse == null) {
            addLogMessage(logMessages, "No response received from the chat model");
            return new StructuredResponse("", logMessages, distinctDocuments);
        }
        String responseText = getContentFromChatResponse(chatResponse);
        Integer promptTokens = chatResponse.getMetadata().getUsage().getPromptTokens();
        Integer completionTokens = chatResponse.getMetadata().getUsage().getCompletionTokens();

        addLogMessage(logMessages, "Received response in %d ms [promptTokens: %d, completionTokens: %d]:\n%s".formatted(
                System.currentTimeMillis() - start, promptTokens, completionTokens, abbreviate(responseText, 100)));

        return new StructuredResponse(responseText, logMessages, distinctDocuments);
    }

    private List<Document> getDistinctDocuments(List<Document> documents) {
        Set<Object> seen = new HashSet<>();
        return documents.stream()
                .sorted((d1, d2) -> {
                    Double rerankScore1 = (Double) d1.getMetadata().get("rerankScore");
                    Double rerankScore2 = (Double) d2.getMetadata().get("rerankScore");
                    if (rerankScore1 != null && rerankScore2 != null) {
                        return Double.compare(rerankScore2, rerankScore1);
                    } else {
                        return Double.compare(d2.getScore(), d1.getScore());
                    }
                })
                .filter(d -> {
                            if (seen.contains(d.getId())) {
                                return false;
                            }
                            seen.add(d.getId());
                            return true;
                        }
                )
                .toList();
    }

    private void addLogMessage(List<String> logMessages, String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logMessages.add(time + " " + message);
        log.debug(message);
    }

    private static String getContentFromChatResponse(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    private Prompt buildPrompt(String userPrompt, String systemPrompt) {
        return new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));
    }

    private ChatModel buildChatModel(ParametersReader parametersReader) {
        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        if (StringUtils.isBlank(openaiApiKey)) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is not set");
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(openaiApiKey)
                .build();
        OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
                .model(parametersReader.getString("model.name", "gpt-4.1-mini"))
                .temperature(parametersReader.getDouble("model.temperature", 1.0))
//                .maxTokens(200)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(openAiChatOptions)
                .build();
    }

    private ChatClient buildClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
