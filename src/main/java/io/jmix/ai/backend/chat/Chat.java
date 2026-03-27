package io.jmix.ai.backend.chat;

import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface Chat {

    /** @deprecated Use {@link #requestStream} instead. */
    @Deprecated
    StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                         @Nullable Consumer<String> externalLogger);

    default Flux<StreamEventConvHolder> requestStream(String userPrompt, String parametersYaml, @Nullable String conversationId) {
        throw new UnsupportedOperationException("Streaming not supported");
    }

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
