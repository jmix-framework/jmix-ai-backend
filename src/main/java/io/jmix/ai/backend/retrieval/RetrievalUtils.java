package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.chat.EventStreamValueHolder;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RetrievalUtils {
    private RetrievalUtils() {}

    public static String getUrlOrSource(Document document) {
        String url = (String) document.getMetadata().get("url");
        if (url != null)
            return url;
        else
            return (String) document.getMetadata().get("source");
    }

    /**
     * Sorts documents by relevance and keeps only the highest-ranked occurrence of each document ID.
     */
    public static List<Document> getUniqueSortedDocuments(List<Document> documents) {
        Set<String> seenIds = new HashSet<>();
        return SearchResultsFormatter.sortByRelevance(documents).stream()
                .filter(document -> seenIds.add(document.getId()))
                .toList();
    }

    public static List<String> getUrls(List<Document> documents) {
        return documents.stream()
                .map(document -> document.getMetadata().get("url"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    public static String formatRequested(@Nullable EventStreamValueHolder.RequestedRetrieval requested) {
        return requested == null ? ""
                : " (%d results requested, vector fetch widened to %d)"
                        .formatted(requested.results(), requested.vectorFetch());
    }
}
