package io.jmix.ai.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.jmix.ai.backend.chat.EventStreamValueHolder;

/**
 * Public API representation of {@link EventStreamValueHolder}.
 * Exposes only what external consumers need — no internal details like search queries or diagnostics.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StreamEventDto.Conversation.class, name = "conversation"),
        @JsonSubTypes.Type(value = StreamEventDto.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = StreamEventDto.TokensStart.class, name = "tokens_start"),
        @JsonSubTypes.Type(value = StreamEventDto.Content.class, name = "content"),
        @JsonSubTypes.Type(value = StreamEventDto.TokensEnd.class, name = "tokens_end"),
        @JsonSubTypes.Type(value = StreamEventDto.SourcesStart.class, name = "sources_start"),
        @JsonSubTypes.Type(value = StreamEventDto.Metadata.class, name = "metadata")
})
public sealed interface StreamEventDto {

    record Conversation(@JsonProperty("conversation_id") String conversationId) implements StreamEventDto {}

    record ToolCall(String tool) implements StreamEventDto {}

    record TokensStart() implements StreamEventDto {}

    record Content(String text) implements StreamEventDto {}

    record TokensEnd() implements StreamEventDto {}

    record SourcesStart() implements StreamEventDto {}

    record Metadata(String source) implements StreamEventDto {}

    /** Maps internal StreamEvent to public DTO. Returns null for internal-only events. */
    static StreamEventDto fromModel(EventStreamValueHolder event) {
        if (event instanceof EventStreamValueHolder.ToolCallStart toolCall) {
            return new ToolCall(toolCall.tool());
        }
        if (event instanceof EventStreamValueHolder.TokensStart) {
            return new TokensStart();
        }
        if (event instanceof EventStreamValueHolder.Content content) {
            return new Content(content.text());
        }
        if (event instanceof EventStreamValueHolder.TokensEnd) {
            return new TokensEnd();
        }
        if (event instanceof EventStreamValueHolder.SourcesStart) {
            return new SourcesStart();
        }
        if (event instanceof EventStreamValueHolder.Metadata metadata) {
            return new Metadata(metadata.source());
        }
        // Internal-only events — filtered by Objects::nonNull in controller
        return null;
    }
}
