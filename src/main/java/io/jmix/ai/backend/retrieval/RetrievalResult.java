package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.chat.EventStreamValueHolder.DocScore;
import io.jmix.ai.backend.chat.EventStreamValueHolder.RequestedRetrieval;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record RetrievalResult(
        String tool,
        String query,
        @Nullable RequestedRetrieval requested,
        Instant startedAt,
        List<Step> steps,
        List<Document> documents,
        String text,
        Instant endedAt) {

    public static RetrievalResult failed(String tool, String query, Instant startedAt) {
        return new RetrievalResult(tool, query, null, startedAt, List.of(), List.of(), "", Instant.now());
    }

    public long durationMs() {
        return Duration.between(startedAt, endedAt).toMillis();
    }

    public sealed interface Step permits Retrieved, Reranked, Note {

        Instant at();
    }

    public record Retrieved(Instant at, List<DocScore> documents, long durationMs) implements Step {
    }

    public record Reranked(Instant at, List<DocScore> documents, long durationMs) implements Step {
    }

    public record Note(Instant at, String message) implements Step {
    }
}
