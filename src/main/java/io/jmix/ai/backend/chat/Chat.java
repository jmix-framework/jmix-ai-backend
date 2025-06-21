package io.jmix.ai.backend.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class Chat {

    private final ChatClient.Builder chatClientBuilder;
    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    public record Options(boolean useRag, String systemMessage) {
    }
    
    public record StructuredResponse(String text, List<String> sourceLinks) {
    }

    public Chat(ChatClient.Builder chatClientBuilder,
                ChatModel chatModel, VectorStore vectorStore) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    public String requestText(String userPrompt, Options options) {
        ChatClient chatClient = buildClient();

        ChatClient.ChatClientRequestSpec request = chatClient.prompt(buildPrompt(userPrompt, options));
        if (options.useRag) {
            request.advisors(buildAdvisor());
        }

        String response = request.call().content();

        return response;
    }

    public StructuredResponse requestStructured(String userPrompt, Options options) {
        ChatClient chatClient = buildClient();

        ChatClient.ChatClientRequestSpec request = chatClient.prompt(buildPrompt(userPrompt, options));

        CustomDocumentRetriever retriever = null;
        if (options.useRag) {
            retriever = buildCustomDocumentRetriever();
            request.advisors(buildTrackingAdvisor(retriever));
        }

        String response = request.call().content();

        List<String> sourceLinks = null;
        if (retriever != null) {
            sourceLinks = retriever.getRetrievedDocuments().stream()
                    .map(document -> document.getMetadata().get("url"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }

        return new StructuredResponse(response, sourceLinks);
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

    private Advisor buildAdvisor() {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                        .topK(SearchRequest.DEFAULT_TOP_K)
                        .build())
                .build();
    }

    private Advisor buildTrackingAdvisor(DocumentRetriever documentRetriever) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }

    private CustomDocumentRetriever buildCustomDocumentRetriever() {
        return CustomDocumentRetriever.builder()
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .topK(SearchRequest.DEFAULT_TOP_K)
                .vectorStore(vectorStore)
                .build();
    }

    public ChatOptions getModelOptions() {
        return chatModel.getDefaultOptions();
    }
}
