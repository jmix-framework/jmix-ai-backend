package io.jmix.ai.backend.retrieval;

import org.slf4j.Logger;
import org.springframework.ai.document.Document;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Utils {
    private Utils() {}

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

    public static void addLogMessage(Logger log, List<String> logMessages, String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logMessages.add(time + " " + message);
        log.debug(message);
    }
}
