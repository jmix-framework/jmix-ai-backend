package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.vectorstore.Snippet;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Shapes retrieved documents into a context7-like search response: orders results by relevance
 * across tools, trims the list to a token budget and extracts a snippet title and source URL.
 */
public class SearchResultsFormatter {

    /** Rough chars-per-token ratio used for the token budget. */
    static final int CHARS_PER_TOKEN = 4;

    private static final int FALLBACK_TITLE_LENGTH = 100;

    /**
     * Orders documents by rerank score (falling back to the similarity score) descending.
     * The sort is stable, so documents without any score keep their tool order.
     */
    public static List<Document> sortByRelevance(List<Document> documents) {
        return documents.stream()
                .sorted(Comparator.comparingDouble(SearchResultsFormatter::relevance).reversed())
                .toList();
    }

    private static double relevance(Document document) {
        Object rerankScore = document.getMetadata().get("rerankScore");
        if (rerankScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore() != null ? document.getScore() : 0.0;
    }

    /**
     * Keeps the head of the list that fits the token budget; always keeps at least one document.
     * A null or non-positive budget returns the list unchanged.
     */
    public static List<Document> applyTokenBudget(List<Document> documents, @Nullable Integer tokens) {
        if (tokens == null || tokens <= 0) {
            return documents;
        }
        long budgetChars = (long) tokens * CHARS_PER_TOKEN;
        List<Document> result = new ArrayList<>();
        long usedChars = 0;
        for (Document document : documents) {
            int length = document.getText() != null ? document.getText().length() : 0;
            if (!result.isEmpty() && usedChars + length > budgetChars) {
                break;
            }
            result.add(document);
            usedChars += length;
        }
        return result;
    }

    /**
     * Snippet title: the {@link Snippet#TITLE_PREFIX} line if present, otherwise the docPath
     * metadata, otherwise the beginning of the text.
     */
    public static String extractTitle(Document document) {
        String text = document.getText() == null ? "" : document.getText();
        for (String line : text.split("\n", 6)) {
            if (line.startsWith(Snippet.TITLE_PREFIX)) {
                return line.substring(Snippet.TITLE_PREFIX.length()).trim();
            }
        }
        String docPath = Objects.toString(document.getMetadata().get("docPath"), "");
        if (!docPath.isBlank()) {
            return docPath;
        }
        String firstLine = text.strip().split("\n", 2)[0];
        return firstLine.length() > FALLBACK_TITLE_LENGTH
                ? firstLine.substring(0, FALLBACK_TITLE_LENGTH)
                : firstLine;
    }

    /**
     * Source URL of the snippet: the url metadata, falling back to the source identifier.
     */
    public static String extractSource(Document document) {
        return Objects.toString(document.getMetadata().get("url"),
                Objects.toString(document.getMetadata().get("source"), ""));
    }
}
