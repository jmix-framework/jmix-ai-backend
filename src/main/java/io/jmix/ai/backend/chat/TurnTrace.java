package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.retrieval.RetrievalResult;
import io.jmix.ai.backend.retrieval.RetrievalUtils;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class TurnTrace {

    public static final String KEY = "jmix.turnTrace";

    @Nullable
    private final String conversationId;
    @Nullable
    private final Sinks.Many<EventStreamValueHolder> toolEvents;
    private final List<RetrievalResult> retrievals = new CopyOnWriteArrayList<>();
    private final AtomicInteger promptTokens = new AtomicInteger();
    private final AtomicInteger completionTokens = new AtomicInteger();

    private TurnTrace(@Nullable String conversationId, @Nullable Sinks.Many<EventStreamValueHolder> toolEvents) {
        this.conversationId = conversationId;
        this.toolEvents = toolEvents;
    }

    public static TurnTrace forCall(@Nullable String conversationId) {
        return new TurnTrace(conversationId, null);
    }

    public static TurnTrace forStream(@Nullable String conversationId) {
        return new TurnTrace(conversationId, Sinks.many().unicast().onBackpressureBuffer());
    }

    public static TurnTrace of(ToolContext toolContext) {
        return Objects.requireNonNull(from(toolContext.getContext()), "The tool context has no TurnTrace");
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
        emit(new EventStreamValueHolder.ToolCallStart(tool, query, requested));
    }

    public void addRetrieval(RetrievalResult result) {
        retrievals.add(result);
        for (RetrievalResult.Step step : result.steps()) {
            switch (step) {
                case RetrievalResult.Retrieved retrieved -> emit(new EventStreamValueHolder.ToolRetrieved(
                        result.tool(), retrieved.documents(), retrieved.durationMs()));
                case RetrievalResult.Reranked reranked -> emit(new EventStreamValueHolder.ToolReranked(
                        result.tool(), reranked.documents(), reranked.durationMs()));
                case RetrievalResult.Note ignored -> {
                }
            }
        }
        emit(new EventStreamValueHolder.ToolCallEnd(result.tool(), result.durationMs()));
    }

    public Flux<EventStreamValueHolder> toolEvents() {
        return toolEvents != null ? toolEvents.asFlux() : Flux.empty();
    }

    public void completeToolEvents() {
        if (toolEvents != null) {
            toolEvents.tryEmitComplete();
        }
    }

    public List<RetrievalResult> retrievals() {
        return List.copyOf(retrievals);
    }

    public List<Document> documents() {
        return retrievals.stream()
                .flatMap(result -> result.documents().stream())
                .toList();
    }

    public List<String> sourceLinks() {
        return RetrievalUtils.getUrls(documents())
                .stream()
                .distinct()
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

    private void emit(EventStreamValueHolder event) {
        if (toolEvents != null) {
            toolEvents.tryEmitNext(event);
        }
    }
}
