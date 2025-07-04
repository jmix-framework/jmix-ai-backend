package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class Chat {

    private static final Logger log = LoggerFactory.getLogger(Chat.class);

    private final VectorStore vectorStore;
    private final ParametersRepository parametersRepository;

    public record StructuredResponse(String text, @Nullable List<Document> retrievedDocuments, @Nullable List<String> sourceLinks) {

        public StructuredResponse(String text, @Nullable List<Document> retrievedDocuments) {
            this(text, retrievedDocuments, getSourceLinks(retrievedDocuments));
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

    public Chat(VectorStore vectorStore, ParametersRepository parametersRepository) {
        this.vectorStore = vectorStore;
        this.parametersRepository = parametersRepository;
    }

    public StructuredResponse requestStructured(String userPrompt, Parameters parameters) {
        long start = System.currentTimeMillis();
        log.debug("Sending prompt: {}", StringUtils.abbreviate(userPrompt, 200));

        ParametersReader parametersReader = parametersRepository.getReader(parameters);

        ChatModel chatModel = buildChatModel(parametersReader);

        ChatClient chatClient = buildClient(chatModel);

        ChatClient.ChatClientRequestSpec request = chatClient.prompt(buildPrompt(userPrompt, parametersReader));

        List<Document> retrievedDocuments = new ArrayList<>();
        if (!parametersReader.getBoolean("useTools")) {
            log.debug("Using {} with RAG", chatModel.getDefaultOptions());
            request.advisors(buildRagAdvisor(parametersReader, retrievedDocuments));
        } else {
            log.debug("Using {} with tools", chatModel.getDefaultOptions());
            DocsTool docsTool = new DocsTool(vectorStore, parametersReader);
            UiSamplesTool uiSamplesTool = new UiSamplesTool(vectorStore, parametersReader);
            TrainingsTool trainingsTool = new TrainingsTool(vectorStore, parametersReader);
            request.toolCallbacks(docsTool.getToolCallback(), uiSamplesTool.getToolCallback(), trainingsTool.getToolCallback());
        }

        String response = request.call().content();

        log.debug("Received response in {}ms: {}", System.currentTimeMillis() - start, StringUtils.abbreviate(response, 100));

        return new StructuredResponse(response, retrievedDocuments);
    }

    private Prompt buildPrompt(String userPrompt, ParametersReader parametersReader) {
        return new Prompt(List.of(
                new SystemMessage(parametersReader.getString("systemMessage")),
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

    private Advisor buildRagAdvisor(ParametersReader parametersReader, List<Document> retrievedDocuments) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(buildDocumentRetriever(parametersReader))
                .documentPostProcessors(new CustomDocumentPostProcessor(parametersReader, retrievedDocuments))
                .build();
    }

    private DocumentRetriever buildDocumentRetriever(ParametersReader parametersReader) {
        return VectorStoreDocumentRetriever.builder()
                .similarityThreshold(parametersReader.getDouble("rag.similarityThreshold"))
                .topK(parametersReader.getInteger("rag.topK"))
                .vectorStore(vectorStore)
                .build();
    }
}
