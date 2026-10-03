package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.retrieval.RetrievalResult;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class TurnTrace {

    public static final String KEY = "jmix.turnTrace";

    @Nullable
    private final String conversationId;
    private final List<RetrievalResult> retrievals = new CopyOnWriteArrayList<>();
    private final AtomicInteger promptTokens = new AtomicInteger();
    private final AtomicInteger completionTokens = new AtomicInteger();
    private final Sinks.Many<EventStreamValueHolder> toolEvents = Sinks.many().unicast().onBackpressureBuffer();

    public TurnTrace(@Nullable String conversationId) {
        this.conversationId = conversationId;
    }

    @Nullable
    public static TurnTrace from(@Nullable ToolContext toolContext) {
        return toolContext != null ? from(toolContext.getContext()) : null;
    }

    @Nullable
    public static TurnTrace from(Map<String, Object> context) {
        return context.get(KEY) instanceof TurnTrace trace ? trace : null;
    }

    @Nullable
    public String conversationId() {
        return conversationId;
    }

    public void toolStarted(String tool, String query, @Nullable EventStreamValueHolder.RequestedRetrieval requested) {
        toolEvents.tryEmitNext(new EventStreamValueHolder.ToolCallStart(tool, query, requested));
    }

    public void addRetrieval(RetrievalResult result) {
        retrievals.add(result);
        for (RetrievalResult.Step step : result.steps()) {
            switch (step) {
                case RetrievalResult.Retrieved retrieved -> toolEvents.tryEmitNext(new EventStreamValueHolder.ToolRetrieved(
                        result.tool(), retrieved.documents(), retrieved.durationMs()));
                case RetrievalResult.Reranked reranked -> toolEvents.tryEmitNext(new EventStreamValueHolder.ToolReranked(
                        result.tool(), reranked.documents(), reranked.durationMs()));
                case RetrievalResult.Note ignored -> {
                }
            }
        }
        toolEvents.tryEmitNext(new EventStreamValueHolder.ToolCallEnd(result.tool(), result.durationMs()));
    }

    public Flux<EventStreamValueHolder> toolEvents() {
        return toolEvents.asFlux();
    }

    public void completeToolEvents() {
        toolEvents.tryEmitComplete();
    }

    public List<RetrievalResult> retrievals() {
        return List.copyOf(retrievals);
    }

    public List<Document> documents() {
        return retrievals.stream()
                .flatMap(result -> result.documents().stream())
                .toList();
    }

    public void addUsage(@Nullable Usage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.getPromptTokens() != null ? usage.getPromptTokens() : 0);
        completionTokens.addAndGet(usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0);
    }

    public int promptTokens() {
        return promptTokens.get();
    }

    public int completionTokens() {
        return completionTokens.get();
    }
}
