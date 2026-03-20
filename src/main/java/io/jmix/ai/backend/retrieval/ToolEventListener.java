package io.jmix.ai.backend.retrieval;

import java.util.List;

public interface ToolEventListener {

    void onToolCall(String toolName, String query);

    void onLog(String message);

    default void onSourcesFound(List<String> sourceUrls) {}
}
