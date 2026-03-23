package io.jmix.ai.backend.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Typed streaming event emitted during chat response generation.
 * Jackson polymorphic serialization includes a {@code "type"} discriminator,
 * so consumers can deserialize without knowing the concrete type upfront.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StreamEvent.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = StreamEvent.TokensStart.class, name = "tokens_start"),
        @JsonSubTypes.Type(value = StreamEvent.Content.class, name = "content"),
        @JsonSubTypes.Type(value = StreamEvent.TokensEnd.class, name = "tokens_end"),
        @JsonSubTypes.Type(value = StreamEvent.SourcesStart.class, name = "sources_start"),
        @JsonSubTypes.Type(value = StreamEvent.Metadata.class, name = "metadata")
})
public sealed interface StreamEvent {

    record ToolCall(String tool, String query, long durationMs) implements StreamEvent {}

    /** Signals that content tokens are about to start. */
    record TokensStart() implements StreamEvent {}

    record Content(String text) implements StreamEvent {}

    /** Signals that all content tokens have been emitted. */
    record TokensEnd() implements StreamEvent {}

    /** Signals that source URLs are about to start. Only emitted if there are sources. */
    record SourcesStart() implements StreamEvent {}

    record Metadata(String source) implements StreamEvent {}
}
