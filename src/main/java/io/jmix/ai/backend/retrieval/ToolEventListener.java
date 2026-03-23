package io.jmix.ai.backend.retrieval;


public interface ToolEventListener {

    /** Called when a tool finishes execution with full results. */
    void onToolCall(String toolName, String query, long durationMs);

    void onLog(String message);
}
