package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.chat.StreamEvent;

import java.util.List;

public interface ToolEventListener {

    void onToolCallStart(String tool, String query);

    void onToolRetrieved(String tool, List<StreamEvent.DocScore> documents, long durationMs);

    void onToolReranked(String tool, List<StreamEvent.DocScore> documents, long durationMs);

    void onToolCallEnd(String tool, long totalDurationMs);

    /** Debug messages (filtered out, reranking failed, etc.) — console only. */
    void onLog(String message);
}
