package io.jmix.ai.backend.retrieval;

import java.util.List;

public interface ToolEventListener {

    /** Called when a tool finishes execution with full results. */
    void onToolCall(String toolName, String query, long durationMs);

    void onLog(String message);

    default void onSourcesFound(List<String> sourceUrls) {}
}
