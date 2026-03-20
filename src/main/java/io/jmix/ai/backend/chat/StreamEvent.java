package io.jmix.ai.backend.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Typed streaming event emitted during chat response generation.
 * Jackson polymorphic serialization includes a {@code "type"} discriminator,
 * so consumers can deserialize without knowing the concrete type upfront.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StreamEvent.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = StreamEvent.Content.class, name = "content"),
        @JsonSubTypes.Type(value = StreamEvent.Metadata.class, name = "metadata")
})
public sealed interface StreamEvent {

    record ToolCall(String tool, String query) implements StreamEvent {}

    record Content(String text) implements StreamEvent {}

    record Metadata(List<String> sources) implements StreamEvent {}
}
