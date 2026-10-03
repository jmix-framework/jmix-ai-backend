package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.retrieval.RetrievalResult;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.abbreviate;

final class ChatLogFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ChatLogFormatter() {
    }

    static String requestLine(String model, String userPrompt) {
        return "Model: %s, User prompt: %s".formatted(model, abbreviate(userPrompt, 200));
    }

    static String responseLine(long durationMs, int promptTokens, int completionTokens) {
        return "Received response in %d ms [promptTokens: %d, completionTokens: %d]"
                .formatted(durationMs, promptTokens, completionTokens);
    }

    static List<String> retrievalLines(RetrievalResult result) {
        List<String> lines = new ArrayList<>();
        lines.add(usingLine(result));
        for (RetrievalResult.Step step : result.steps()) {
            lines.add(stepLine(step));
        }
        lines.add(doneLine(result));
        return lines;
    }

    static List<String> callLines(Instant startedAt, String model, String userPrompt, List<RetrievalResult> retrievals) {
        List<String> lines = new ArrayList<>();
        lines.add(timed(startedAt, requestLine(model, userPrompt)));
        for (RetrievalResult result : retrievals) {
            lines.add(timed(result.startedAt(), usingLine(result)));
            for (RetrievalResult.Step step : result.steps()) {
                lines.add(timed(step.at(), stepLine(step)));
            }
            lines.add(timed(result.endedAt(), doneLine(result)));
        }
        return lines;
    }

    static String callResponseLine(@Nullable String answer, long durationMs, int promptTokens, int completionTokens) {
        return answer == null
                ? "No response received from the chat model"
                : responseLine(durationMs, promptTokens, completionTokens) + ":\n" + abbreviate(answer, 100);
    }

    static List<String> streamLines(Instant startedAt, String model, String userPrompt, List<RetrievalResult> retrievals) {
        List<String> lines = new ArrayList<>();
        lines.add(timed(startedAt, "Model: %s, User prompt: %s".formatted(model, userPrompt)));
        for (RetrievalResult result : retrievals) {
            lines.add(timed(result.startedAt(), usingLine(result)));
            for (RetrievalResult.Step step : result.steps()) {
                switch (step) {
                    case RetrievalResult.Retrieved retrieved -> lines.add(timed(step.at(),
                            "Found documents (%d) in %d ms: %s".formatted(retrieved.documents().size(),
                                    retrieved.durationMs(), formatDocScores(retrieved.documents()))));
                    case RetrievalResult.Reranked reranked -> lines.add(timed(step.at(),
                            "Reranked documents (%d) in %d ms: %s".formatted(reranked.documents().size(),
                                    reranked.durationMs(), formatDocScores(reranked.documents()))));
                    case RetrievalResult.Note ignored -> {
                    }
                }
            }
            lines.add(timed(result.endedAt(), doneLine(result)));
        }
        return lines;
    }

    static String timed(Instant at, String line) {
        return LocalTime.ofInstant(at, ZoneId.systemDefault()).format(TIME) + " " + line;
    }

    static String usingLine(RetrievalResult result) {
        return usingLine(result.tool(), result.query(), result.requested());
    }

    static String usingLine(String tool, String query, @Nullable EventStreamValueHolder.RequestedRetrieval requested) {
        return ">>> Using %s: %s%s".formatted(tool, query, formatRequested(requested));
    }

    static String stepLine(RetrievalResult.Step step) {
        return switch (step) {
            case RetrievalResult.Retrieved retrieved ->
                    "Found documents (%d): %s".formatted(retrieved.documents().size(), formatDocScores(retrieved.documents()));
            case RetrievalResult.Reranked reranked ->
                    "Reranked documents (%d): %s".formatted(reranked.documents().size(), formatDocScores(reranked.documents()));
            case RetrievalResult.Note note -> note.message();
        };
    }

    static String doneLine(RetrievalResult result) {
        return "%s done in %d ms".formatted(result.tool(), result.durationMs());
    }

    static String formatDocScores(List<EventStreamValueHolder.DocScore> docs) {
        return docs.stream()
                .map(d -> "(%.3f) %s".formatted(d.score(), d.url()))
                .toList()
                .toString();
    }

    private static String formatRequested(@Nullable EventStreamValueHolder.RequestedRetrieval requested) {
        return requested == null ? ""
                : " (%d results requested, vector fetch widened to %d)"
                        .formatted(requested.results(), requested.vectorFetch());
    }
}
