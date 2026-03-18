package io.jmix.ai.backend.chat;

import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface Chat {

    default StructuredResponse requestStructuredStreaming(String userPrompt, String parametersYaml,
                                                          @Nullable String conversationId,
                                                          @Nullable Consumer<String> chunkConsumer,
                                                          @Nullable Consumer<String> externalLogger) {
        return requestStructuredStreaming(userPrompt, parametersYaml, conversationId, null, chunkConsumer, externalLogger);
    }

    default StructuredResponse requestStructuredStreaming(String userPrompt, String parametersYaml,
                                                          @Nullable String conversationId,
                                                          @Nullable BooleanSupplier cancellationRequested,
                                                          @Nullable Consumer<String> chunkConsumer,
                                                          @Nullable Consumer<String> externalLogger) {
        StructuredResponse response = requestStructured(userPrompt, parametersYaml, conversationId, externalLogger);
        if (chunkConsumer != null && response.text() != null) {
            chunkConsumer.accept(response.text());
        }
        return response;
    }

    StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                         @Nullable Consumer<String> externalLogger);

    record StructuredResponse(
            String text,
            List<String> logMessages,
            @Nullable List<Document> retrievedDocuments,
            @Nullable List<String> sourceLinks,
            int promptTokens,
            int completionTokens,
            int responseTime
    ) {

        public StructuredResponse(String text, List<String> logMessages, @Nullable List<Document> retrievedDocuments,
                                  int promptTokens, int completionTokens, int responseTime) {
            this(text, logMessages, retrievedDocuments, getSourceLinks(retrievedDocuments),
                    promptTokens, completionTokens, responseTime);
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
}
