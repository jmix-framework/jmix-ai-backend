package io.jmix.ai.backend.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.jmix.ai.backend.chat.StreamEvent;
import jakarta.validation.constraints.NotNull;

/**
 * Public API representation of {@link StreamEvent}.
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
    static StreamEventDto fromModel(StreamEvent event) {
        return switch (event) {
            case StreamEvent.ToolCallStart tc -> new ToolCall(tc.tool());
            case StreamEvent.TokensStart ignored -> new TokensStart();
            case StreamEvent.Content c -> new Content(c.text());
            case StreamEvent.TokensEnd ignored -> new TokensEnd();
            case StreamEvent.SourcesStart ignored -> new SourcesStart();
            case StreamEvent.Metadata m -> new Metadata(m.source());
            // Internal-only events — filtered by Objects::nonNull in controller
            case StreamEvent.RequestInfo ignored -> null;
            case StreamEvent.ToolRetrieved ignored -> null;
            case StreamEvent.ToolReranked ignored -> null;
            case StreamEvent.ToolCallEnd ignored -> null;
            case StreamEvent.RequestEnd ignored -> null;
        };
    }
}
