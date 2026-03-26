package io.jmix.ai.backend.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Typed streaming events emitted during chat response generation.
 *
 * <p>Full event sequence:
 * <pre>
 * RequestInfo
 *   → ToolCallStart → ToolRetrieved → ToolReranked → ToolCallEnd   (per tool, may repeat)
 *   → TokensStart → Content* → TokensEnd
 *   → [SourcesStart → Metadata*]
 * → RequestEnd
 * </pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StreamEvent.RequestInfo.class, name = "request_info"),
        @JsonSubTypes.Type(value = StreamEvent.ToolCallStart.class, name = "tool_call_start"),
        @JsonSubTypes.Type(value = StreamEvent.ToolRetrieved.class, name = "tool_retrieved"),
        @JsonSubTypes.Type(value = StreamEvent.ToolReranked.class, name = "tool_reranked"),
        @JsonSubTypes.Type(value = StreamEvent.ToolCallEnd.class, name = "tool_call_end"),
        @JsonSubTypes.Type(value = StreamEvent.TokensStart.class, name = "tokens_start"),
        @JsonSubTypes.Type(value = StreamEvent.Content.class, name = "content"),
        @JsonSubTypes.Type(value = StreamEvent.TokensEnd.class, name = "tokens_end"),
        @JsonSubTypes.Type(value = StreamEvent.SourcesStart.class, name = "sources_start"),
        @JsonSubTypes.Type(value = StreamEvent.Metadata.class, name = "metadata"),
        @JsonSubTypes.Type(value = StreamEvent.RequestEnd.class, name = "request_end")
})
public sealed interface StreamEvent {

    // --- Request lifecycle ---

    record RequestInfo(String conversationId, String model, String userPrompt) implements StreamEvent {}

    record RequestEnd(int promptTokens, int completionTokens, long totalDurationMs) implements StreamEvent {}

    // --- Tool execution lifecycle ---

    record ToolCallStart(String tool, String query) implements StreamEvent {}

    record ToolRetrieved(String tool, List<DocScore> documents, long durationMs) implements StreamEvent {}

    record ToolReranked(String tool, List<DocScore> documents, long durationMs) implements StreamEvent {}

    record ToolCallEnd(String tool, long totalDurationMs) implements StreamEvent {}

    record DocScore(double score, String url) {}

    // --- Content ---

    record TokensStart() implements StreamEvent {}

    record Content(String text) implements StreamEvent {}

    record TokensEnd() implements StreamEvent {}

    // --- Sources ---

    record SourcesStart() implements StreamEvent {}

    record Metadata(String source) implements StreamEvent {}
}
