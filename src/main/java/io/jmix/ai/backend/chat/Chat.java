package io.jmix.ai.backend.chat;

import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface Chat {

    StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                         @Nullable Consumer<String> externalLogger);

    record StructuredResponse(
            String text,
            List<String> logMessages,
            @Nullable List<Document> retrievedDocuments,
            @Nullable List<String> sourceLinks,
            int promptTokens,
            int completionTokens
    ) {

        public StructuredResponse(String text, List<String> logMessages, @Nullable List<Document> retrievedDocuments, int promptTokens, int completionTokens) {
            this(text, logMessages, retrievedDocuments, getSourceLinks(retrievedDocuments), promptTokens, completionTokens);
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
