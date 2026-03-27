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
        @JsonSubTypes.Type(value = EventStreamValueHolder.RequestInfo.class, name = "request_info"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.ToolCallStart.class, name = "tool_call_start"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.ToolRetrieved.class, name = "tool_retrieved"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.ToolReranked.class, name = "tool_reranked"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.ToolCallEnd.class, name = "tool_call_end"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.TokensStart.class, name = "tokens_start"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.Content.class, name = "content"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.TokensEnd.class, name = "tokens_end"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.SourcesStart.class, name = "sources_start"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.Metadata.class, name = "metadata"),
        @JsonSubTypes.Type(value = EventStreamValueHolder.RequestEnd.class, name = "request_end")
})
public sealed interface EventStreamValueHolder {

    // --- Request lifecycle ---

    record RequestInfo(String model, String userPrompt) implements EventStreamValueHolder {}

    record RequestEnd(int promptTokens, int completionTokens, long totalDurationMs) implements EventStreamValueHolder {}

    // --- Tool execution lifecycle ---

    record ToolCallStart(String tool, String query) implements EventStreamValueHolder {}

    record ToolRetrieved(String tool, List<DocScore> documents, long durationMs) implements EventStreamValueHolder {}

    record ToolReranked(String tool, List<DocScore> documents, long durationMs) implements EventStreamValueHolder {}

    record ToolCallEnd(String tool, long totalDurationMs) implements EventStreamValueHolder {}

    record DocScore(double score, String url) {}

    // --- Content ---

    record TokensStart() implements EventStreamValueHolder {}

    record Content(String text) implements EventStreamValueHolder {}

    record TokensEnd() implements EventStreamValueHolder {}

    // --- Sources ---

    record SourcesStart() implements EventStreamValueHolder {}

    record Metadata(String source) implements EventStreamValueHolder {}
}
