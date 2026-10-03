package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLogFormatterTest {

    private static final String MODEL = "OpenAiChatOptions: {model: gpt-5}";
    private static final String QUESTION = "How to show a notification?";
    private static final String TOOL = "documentation_retriever";
    private static final String QUERY = "notifications";
    private static final String RULE_NOTE = "Rule 'BPM Add-on' filtered out https://docs.jmix.io/bpm.html";
    private static final EventStreamValueHolder.DocScore FOUND = new EventStreamValueHolder.DocScore(0.9, "https://docs.jmix.io/a.html");
    private static final EventStreamValueHolder.DocScore RERANKED = new EventStreamValueHolder.DocScore(0.95, "https://docs.jmix.io/a.html");
    private static final EventStreamValueHolder.RequestedRetrieval REQUESTED = new EventStreamValueHolder.RequestedRetrieval(6, 24);

    @Test
    void callLinesKeepTheStructuredChatLogFormat() {
        // Arrange
        RetrievalResult result = retrievalResult();

        // Act
        List<String> lines = ChatLogFormatter.callLines(at("10:00:00"), MODEL, QUESTION, List.of(result));

        // Assert
        assertThat(lines)
                .containsExactly(
                        "10:00:00 Model: " + MODEL + ", User prompt: " + QUESTION,
                        "10:00:01 >>> Using documentation_retriever: notifications (6 results requested, vector fetch widened to 24)",
                        "10:00:02 Found documents (1): [" + docScore(FOUND) + "]",
                        "10:00:02 " + RULE_NOTE,
                        "10:00:03 Reranked documents (1): [" + docScore(RERANKED) + "]",
                        "10:00:04 documentation_retriever done in 3000 ms");
    }

    @Test
    void streamLinesKeepTheStreamedChatLogFormat() {
        // Arrange
        RetrievalResult result = retrievalResult();

        // Act
        List<String> lines = ChatLogFormatter.streamLines(at("10:00:00"), MODEL, QUESTION, List.of(result));

        // Assert
        assertThat(lines)
                .containsExactly(
                        "10:00:00 Model: " + MODEL + ", User prompt: " + QUESTION,
                        "10:00:01 >>> Using documentation_retriever: notifications (6 results requested, vector fetch widened to 24)",
                        "10:00:02 Found documents (1) in 120 ms: [" + docScore(FOUND) + "]",
                        "10:00:03 Reranked documents (1) in 300 ms: [" + docScore(RERANKED) + "]",
                        "10:00:04 documentation_retriever done in 3000 ms");
    }

    private static RetrievalResult retrievalResult() {
        return new RetrievalResult(TOOL, QUERY, REQUESTED, at("10:00:01"),
                List.of(
                        new RetrievalResult.Retrieved(at("10:00:02"), List.of(FOUND), 120),
                        new RetrievalResult.Note(at("10:00:02"), RULE_NOTE),
                        new RetrievalResult.Reranked(at("10:00:03"), List.of(RERANKED), 300)),
                List.of(), "", at("10:00:04"));
    }

    private static Instant at(String time) {
        return LocalDate.now()
                .atTime(LocalTime.parse(time))
                .atZone(ZoneId.systemDefault())
                .toInstant();
    }

    private static String docScore(EventStreamValueHolder.DocScore docScore) {
        return "(%.3f) %s".formatted(docScore.score(), docScore.url());
    }
}
