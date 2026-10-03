package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.retrieval.RetrievalResult;
import io.jmix.ai.backend.retrieval.RetrievalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ChatLogAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String MODEL = "jmix.chatLog.model";
    public static final String SAVE = "jmix.chatLog.save";
    public static final String LOG_LINES = "jmix.chatLog.lines";
    public static final String RETRIEVAL_LINES = "jmix.chatLog.retrievalLines";

    private static final Logger log = LoggerFactory.getLogger(ChatLogAdvisor.class);
    private static final String CONVERSATION_ID_KEY = "cid";

    private final ChatLogManager chatLogManager;
    private final Scheduler saveScheduler;
    private final int order;

    public ChatLogAdvisor(ChatLogManager chatLogManager, Scheduler saveScheduler, int order) {
        this.chatLogManager = chatLogManager;
        this.saveScheduler = saveScheduler;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        TurnTrace trace = TurnTrace.from(chatClientRequest.context());
        if (trace == null) {
            return callAdvisorChain.nextCall(chatClientRequest);
        }
        return callAndLog(chatClientRequest, callAdvisorChain, trace);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        TurnTrace trace = TurnTrace.from(chatClientRequest.context());
        if (trace == null) {
            return streamAdvisorChain.nextStream(chatClientRequest);
        }
        return Flux.defer(() -> {
            Instant startedAt = Instant.now();
            AtomicInteger finishedTools = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            logInfo(chatClientRequest, ChatLogFormatter.requestLine(
                    modelOf(chatClientRequest), userPromptOf(chatClientRequest)));

            return streamAdvisorChain.nextStream(chatClientRequest)
                    .doOnNext(response -> logToolEvent(chatClientRequest, trace, finishedTools,
                            ToolEventsAdvisor.eventOf(response.chatResponse())))
                    .doOnError(failure::set)
                    .doFinally(signal -> finishStream(chatClientRequest, trace, startedAt, signal, failure.get()));
        });
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    private ChatClientResponse callAndLog(ChatClientRequest request, CallAdvisorChain callAdvisorChain, TurnTrace trace) {
        Instant startedAt = Instant.now();
        try {
            ChatClientResponse response = callAdvisorChain.nextCall(request);

            String answer = textOf(response.chatResponse());
            long durationMs = System.currentTimeMillis() - startedAt.toEpochMilli();
            List<String> lines = new ArrayList<>(callLines(request, trace, startedAt));
            lines.add(ChatLogFormatter.timed(Instant.now(), ChatLogFormatter.callResponseLine(
                    answer, durationMs, trace.promptTokens(), trace.completionTokens())));
            finishCall(request, trace, lines, durationMs);

            return response.mutate()
                    .context(LOG_LINES, lines)
                    .context(RETRIEVAL_LINES, retrievalLines(trace))
                    .build();
        } catch (RuntimeException e) {
            List<String> lines = new ArrayList<>(callLines(request, trace, startedAt));
            lines.add(ChatLogFormatter.timed(Instant.now(), "Request failed: " + e));
            finishCall(request, trace, lines, System.currentTimeMillis() - startedAt.toEpochMilli());
            throw e;
        }
    }

    private void finishCall(ChatClientRequest request, TurnTrace trace, List<String> lines, long durationMs) {
        lines.forEach(line -> logDebug(request, line.substring(line.indexOf(' ') + 1)));
        if (isSaveEnabled(request)) {
            List<String> sourceLinks = RetrievalUtils.getUrls(RetrievalUtils.getUniqueSortedDocuments(trace.documents()));
            save(trace, lines, String.join(",", sourceLinks), durationMs);
        }
    }

    private void finishStream(ChatClientRequest request,
                              TurnTrace trace,
                              Instant startedAt,
                              SignalType signal,
                              @Nullable Throwable failure) {
        long durationMs = System.currentTimeMillis() - startedAt.toEpochMilli();
        String lastLine = switch (signal) {
            case ON_COMPLETE -> ChatLogFormatter.responseLine(durationMs, trace.promptTokens(), trace.completionTokens());
            case ON_ERROR -> "Request failed: " + failure;
            default -> "Request cancelled";
        };
        logInfo(request, lastLine);

        if (!isSaveEnabled(request)) {
            return;
        }
        List<String> lines = new ArrayList<>(ChatLogFormatter.streamLines(
                startedAt, modelOf(request), userPromptOf(request), trace.retrievals()));
        lines.add(ChatLogFormatter.timed(Instant.now(), lastLine));
        List<String> sourceLinks = trace.sourceLinks();
        saveScheduler.schedule(() -> save(trace, lines,
                sourceLinks.isEmpty() ? null : String.join(",", sourceLinks), durationMs));
    }

    private void logToolEvent(ChatClientRequest request,
                              TurnTrace trace,
                              AtomicInteger finishedTools,
                              @Nullable EventStreamValueHolder event) {
        switch (event) {
            case EventStreamValueHolder.ToolCallStart start ->
                    logInfo(request, ChatLogFormatter.usingLine(start.tool(), start.query(), start.requested()));
            case EventStreamValueHolder.ToolRetrieved retrieved -> logInfo(request, "Found documents (%d): %s"
                    .formatted(retrieved.documents().size(), ChatLogFormatter.formatDocScores(retrieved.documents())));
            case EventStreamValueHolder.ToolReranked reranked -> logInfo(request, "Reranked documents (%d): %s"
                    .formatted(reranked.documents().size(), ChatLogFormatter.formatDocScores(reranked.documents())));
            case EventStreamValueHolder.ToolCallEnd end -> {
                RetrievalResult finished = trace.retrievals().get(finishedTools.getAndIncrement());
                notesOf(finished).forEach(note -> logDebug(request, note));
                logInfo(request, "%s done in %d ms".formatted(end.tool(), end.totalDurationMs()));
            }
            case null, default -> {
            }
        }
    }

    private void save(TurnTrace trace, List<String> lines, @Nullable String sources, long durationMs) {
        try {
            chatLogManager.save(trace.conversationId(), lines, sources,
                    trace.promptTokens(), trace.completionTokens(), (int) durationMs);
        } catch (RuntimeException e) {
            log.error("Failed to save the chat log", e);
        }
    }

    private static List<String> notesOf(RetrievalResult result) {
        return result.steps()
                .stream()
                .filter(RetrievalResult.Note.class::isInstance)
                .map(step -> ((RetrievalResult.Note) step).message())
                .toList();
    }

    private static List<String> callLines(ChatClientRequest request, TurnTrace trace, Instant startedAt) {
        return ChatLogFormatter.callLines(startedAt, modelOf(request), userPromptOf(request), trace.retrievals());
    }

    private static List<String> retrievalLines(TurnTrace trace) {
        return trace.retrievals()
                .stream()
                .flatMap(result -> ChatLogFormatter.retrievalLines(result).stream())
                .toList();
    }

    private static boolean isSaveEnabled(ChatClientRequest request) {
        return Boolean.TRUE.equals(request.context().get(SAVE));
    }

    private static String modelOf(ChatClientRequest request) {
        return Objects.toString(request.context().get(MODEL), "");
    }

    private static String userPromptOf(ChatClientRequest request) {
        return Objects.toString(request.prompt().getUserMessage().getText(), "");
    }

    @Nullable
    private static String textOf(@Nullable ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        return Objects.requireNonNullElse(chatResponse.getResult().getOutput().getText(), "");
    }

    private static void logInfo(ChatClientRequest request, String line) {
        log.atInfo()
                .addKeyValue(CONVERSATION_ID_KEY, conversationIdOf(request))
                .log(line);
    }

    private static void logDebug(ChatClientRequest request, String line) {
        log.atDebug()
                .addKeyValue(CONVERSATION_ID_KEY, conversationIdOf(request))
                .log(line);
    }

    private static String conversationIdOf(ChatClientRequest request) {
        return Objects.toString(request.context().get(ChatMemory.CONVERSATION_ID), "");
    }
}
