package io.jmix.ai.backend.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.jmix.ai.backend.chat.StreamEvent;

/**
 * Public API representation of {@link StreamEvent}.
 * Exposes only what external consumers need — no internal details like search queries.
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

    static StreamEventDto fromModel(StreamEvent event) {
        return switch (event) {
            case StreamEvent.ToolCall tc -> new ToolCall(tc.tool());
            case StreamEvent.TokensStart ignored -> new TokensStart();
            case StreamEvent.Content c -> new Content(c.text());
            case StreamEvent.TokensEnd ignored -> new TokensEnd();
            case StreamEvent.SourcesStart ignored -> new SourcesStart();
            case StreamEvent.Metadata m -> new Metadata(m.source());
        };
    }
}
