package io.jmix.ai.backend.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.jmix.ai.backend.chat.EventStreamValueHolder;
import jakarta.validation.constraints.NotNull;

/**
 * Public API representation of {@link EventStreamValueHolder}.
 * Exposes only what external consumers need — no internal details like search queries or diagnostics.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StreamEventDto.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = StreamEventDto.TokensStart.class, name = "tokens_start"),
        @JsonSubTypes.Type(value = StreamEventDto.Content.class, name = "content"),
        @JsonSubTypes.Type(value = StreamEventDto.TokensEnd.class, name = "tokens_end"),
        @JsonSubTypes.Type(value = StreamEventDto.SourcesStart.class, name = "sources_start"),
        @JsonSubTypes.Type(value = StreamEventDto.Metadata.class, name = "metadata")
})
public sealed interface StreamEventDto {

    record ToolCall(String tool) implements StreamEventDto {}

    record TokensStart() implements StreamEventDto {}

    record Content(String text) implements StreamEventDto {}

    record TokensEnd() implements StreamEventDto {}

    record SourcesStart() implements StreamEventDto {}

    record Metadata(String source) implements StreamEventDto {}

    /** Maps internal StreamEvent to public DTO. Returns null for internal-only events. */
    static StreamEventDto fromModel(EventStreamValueHolder event) {
        return switch (event) {
            case EventStreamValueHolder.ToolCallStart tc -> new ToolCall(tc.tool());
            case EventStreamValueHolder.TokensStart ignored -> new TokensStart();
            case EventStreamValueHolder.Content c -> new Content(c.text());
            case EventStreamValueHolder.TokensEnd ignored -> new TokensEnd();
            case EventStreamValueHolder.SourcesStart ignored -> new SourcesStart();
            case EventStreamValueHolder.Metadata m -> new Metadata(m.source());
            // Internal-only events — filtered by Objects::nonNull in controller
            case EventStreamValueHolder.RequestInfo ignored -> null;
            case EventStreamValueHolder.ToolRetrieved ignored -> null;
            case EventStreamValueHolder.ToolReranked ignored -> null;
            case EventStreamValueHolder.ToolCallEnd ignored -> null;
            case EventStreamValueHolder.RequestEnd ignored -> null;
        };
    }
}
