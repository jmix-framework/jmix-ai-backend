package io.jmix.ai.backend.chat;

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
                            if (seen.contains(d.getId())) {
                                return false;
                            }
                            seen.add(d.getId());
                            return true;
                        }
                )
                .toList();
    }

    public static void addLogMessage(Logger log, List<String> logMessages, String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logMessages.add(time + " " + message);
        log.debug(message);
    }
}
