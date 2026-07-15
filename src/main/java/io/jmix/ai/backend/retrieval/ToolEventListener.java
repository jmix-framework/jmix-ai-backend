package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.chat.EventStreamValueHolder;

import java.util.List;

public interface ToolEventListener {

    void onToolCallStart(String tool, String query);

    void onToolRetrieved(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs);

    void onToolReranked(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs);

    void onToolCallEnd(String tool, long totalDurationMs);

    /** Debug messages (filtered out, reranking failed, etc.) — console only. */
    void onLog(String message);

    /**
     * Wraps a listener with MDC "cid" propagation during tool execution.
     * MDC is set on {@code onToolCallStart} and removed on {@code onToolCallEnd},
     * so any logging between these calls (e.g. Reranker) includes conversation id.
     */
    static ToolEventListener withMdcDuringToolExecution(String conversationId, ToolEventListener delegate) {
        return new ToolEventListener() {
            @Override
            public void onToolCallStart(String tool, String query) {
                org.slf4j.MDC.put("cid", conversationId);
                delegate.onToolCallStart(tool, query);
            }

            @Override
            public void onToolRetrieved(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                delegate.onToolRetrieved(tool, documents, durationMs);
            }

            @Override
            public void onToolReranked(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                delegate.onToolReranked(tool, documents, durationMs);
            }

            @Override
            public void onToolCallEnd(String tool, long totalDurationMs) {
                delegate.onToolCallEnd(tool, totalDurationMs);
                org.slf4j.MDC.remove("cid");
            }

            @Override
            public void onLog(String message) {
                delegate.onLog(message);
            }
        };
    }
}
