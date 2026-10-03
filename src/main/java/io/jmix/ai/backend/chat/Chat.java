package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.retrieval.RetrievalUtils;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

public interface Chat {

    /** @deprecated Use {@link #requestStream} instead. */
    @Deprecated
    StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                         @Nullable JmixVersion jmixVersion, boolean saveChatLog);

    default Flux<StreamingEvent> requestStream(String userPrompt, String parametersYaml,
                                               @Nullable String conversationId, @Nullable JmixVersion jmixVersion) {
        throw new UnsupportedOperationException("Streaming not supported");
    }

    record StructuredResponse(
            String text,
            List<String> logMessages,
            List<String> retrievalLog,
            @Nullable List<Document> retrievedDocuments,
            @Nullable List<String> sourceLinks,
            int promptTokens,
            int completionTokens,
            int responseTime
    ) {

        public StructuredResponse(String text, List<String> logMessages, List<String> retrievalLog,
                                  @Nullable List<Document> retrievedDocuments,
                                  int promptTokens, int completionTokens, int responseTime) {
            this(text, logMessages, retrievalLog, retrievedDocuments,
                    retrievedDocuments != null ? RetrievalUtils.getUrls(retrievedDocuments) : null,
                    promptTokens, completionTokens, responseTime);
        }

        public StructuredResponse(String text, List<String> logMessages, @Nullable List<Document> retrievedDocuments,
                                  int promptTokens, int completionTokens, int responseTime) {
            this(text, logMessages, List.of(), retrievedDocuments, promptTokens, completionTokens, responseTime);
        }
    }
}
