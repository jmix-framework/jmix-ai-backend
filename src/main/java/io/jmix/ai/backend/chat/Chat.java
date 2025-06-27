package io.jmix.ai.backend.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class Chat {

    private final ChatClient.Builder chatClientBuilder;
    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    public record Options(boolean useRag, String systemMessage, double similarityThreshold, int topK) {
    }
    
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

    public Chat(ChatClient.Builder chatClientBuilder,
                ChatModel chatModel, VectorStore vectorStore) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    public StructuredResponse requestStructured(String userPrompt, Options options) {
        ChatClient chatClient = buildClient();

        ChatClient.ChatClientRequestSpec request = chatClient.prompt(buildPrompt(userPrompt, options));

        CustomDocumentRetriever retriever = null;
        if (options.useRag) {
            retriever = buildCustomDocumentRetriever(options);
            request.advisors(buildTrackingAdvisor(retriever));
        }

        String response = request.call().content();

        List<Document> retrievedDocuments = retriever != null ? retriever.getRetrievedDocuments() : null;
        return new StructuredResponse(response, retrievedDocuments);
    }

    private Prompt buildPrompt(String userPrompt, Options options) {
        return new Prompt(List.of(
                new SystemMessage(options.systemMessage),
                new UserMessage(userPrompt)
        ));
    }

    private ChatClient buildClient() {
        return chatClientBuilder.build();
    }

    private Advisor buildTrackingAdvisor(DocumentRetriever documentRetriever) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }

    private CustomDocumentRetriever buildCustomDocumentRetriever(Options options) {
        return CustomDocumentRetriever.builder()
                .similarityThreshold(options.similarityThreshold)
                .topK(options.topK)
                .vectorStore(vectorStore)
                .build();
    }

    public ChatOptions getModelOptions() {
        return chatModel.getDefaultOptions();
    }
}
