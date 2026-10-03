package io.jmix.ai.backend.retrieval;

import org.springframework.ai.document.Document;

import java.util.HashSet;
import java.util.List;
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
}
