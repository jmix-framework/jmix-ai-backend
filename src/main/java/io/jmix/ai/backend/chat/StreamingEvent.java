package io.jmix.ai.backend.chat;

import java.time.Instant;

public record StreamingEvent(String conversationId, Instant timestamp, EventStreamValueHolder value) {

    public static StreamingEvent of(String conversationId, EventStreamValueHolder value) {
        return new StreamingEvent(conversationId, Instant.now(), value);
    }
}
