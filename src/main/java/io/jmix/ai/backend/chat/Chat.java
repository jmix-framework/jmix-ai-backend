package io.jmix.ai.backend.chat;

import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface Chat {

    StructuredResponse requestStructured(String userPrompt, String parametersYaml, @Nullable String conversationId,
                                         @Nullable Consumer<String> externalLogger);

    Flux<StreamEvent> requestStream(String userPrompt, String parametersYaml, @Nullable String conversationId);

    /**
     * Renders a StreamEvent as markdown text for display in chat UI.
     * Keeps StreamEvent type resolution in the app classloader (avoids Jmix hot-reload ClassCastException).
     */
    static String renderStreamEvent(StreamEvent event) {
        return switch (event) {
            case StreamEvent.Content c -> c.text();
            case StreamEvent.ToolCall tc -> "_Searching: " + tc.query() + "_\n\n";
            case StreamEvent.Metadata m -> "\n\n---\n**Sources:** " + String.join("\n",
                    m.sources().stream().map(url -> "[%s](%s)".formatted(url, url)).toList());
        };
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
