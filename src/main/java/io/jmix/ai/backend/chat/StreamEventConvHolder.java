package io.jmix.ai.backend.chat;

/**
 * Wraps a {@link StreamEvent} with its conversation ID.
 * Every event in the stream carries the conversation context
 * so consumers (logging, persistence, UI) can access it without shared mutable state.
 */
public record StreamEventConvHolder(String conversationId, StreamEvent event) {}
