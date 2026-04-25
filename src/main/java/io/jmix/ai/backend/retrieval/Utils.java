package io.jmix.ai.backend.retrieval;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.ai.document.Document;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    public static String getDocSourcesAsString(List<Document> documents) {
        return documents.stream()
                .map(document ->
                        "(" + String.format("%.3f", document.getScore()) + ") " + getUrlOrSource(document))
                .toList()
                .toString();
    }

    public static String getRerankResultsAsString(List<Reranker.Result> rerankResults) {
        return rerankResults.stream()
                .map(rr ->
                        "(" + String.format("%.3f", rr.score()) + ") " + getUrlOrSource(rr.document()))
                .toList()
                .toString();
    }

    public static List<Document> getDistinctDocuments(List<Document> documents) {
        Set<Object> seen = new HashSet<>();
        return documents.stream()
                .sorted((d1, d2) -> {
                    Double rerankScore1 = (Double) d1.getMetadata().get("rerankScore");
                    Double rerankScore2 = (Double) d2.getMetadata().get("rerankScore");
                    if (rerankScore1 != null && rerankScore2 != null) {
                        return Double.compare(rerankScore2, rerankScore1);
                    } else {
                        if( d1.getScore() != null && d2.getScore() != null) {
                            return Double.compare(d2.getScore(), d1.getScore());
                        }
                        return 0;
                    }
                })
                .filter(d -> {
                            Object key = getCanonicalDocumentKey(d);
                            if (seen.contains(key)) {
                                return false;
                            }
                            seen.add(key);
                            return true;
                        }
                )
                .toList();
    }

    public static List<Document> getDistinctDocumentsPreserveOrder(List<Document> documents) {
        Set<Object> seen = new LinkedHashSet<>();
        return documents.stream()
                .filter(document -> {
                    Object key = getCanonicalDocumentKey(document);
                    if (seen.contains(key)) {
                        return false;
                    }
                    seen.add(key);
                    return true;
                })
                .toList();
    }

    private static Object getCanonicalDocumentKey(Document document) {
        String documentPath = StringUtils.trimToNull((String) document.getMetadata().get("documentPath"));
        if (documentPath != null) {
            return documentPath;
        }

        String url = StringUtils.trimToNull((String) document.getMetadata().get("url"));
        if (url != null) {
            return stripFragment(url);
        }

        String source = StringUtils.trimToNull((String) document.getMetadata().get("source"));
        if (source != null) {
            return stripFragment(source);
        }

        return document.getId();
    }

    private static String stripFragment(String value) {
        int hashIndex = value.indexOf('#');
        return hashIndex >= 0 ? value.substring(0, hashIndex) : value;
    }

    public static void addLogMessage(Logger log, List<String> logMessages, String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logMessages.add(time + " " + message);
        log.debug(message);
    }
}
