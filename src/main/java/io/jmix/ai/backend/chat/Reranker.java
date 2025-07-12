package io.jmix.ai.backend.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class Reranker {

    public record Result(Document document, double score) {
    }

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private final RestTemplate restTemplate;
    private final String rerankerServiceUrl = "http://localhost:8000/rerank";

    public Reranker() {
        this.restTemplate = new RestTemplate();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public List<Result> rerank(String query, List<Document> documents, int topN) {
        List<String> texts = documents.stream()
                .map(doc -> doc.getText() != null ? doc.getText().trim() : "")
                .filter(content -> !content.isEmpty())
                .toList();
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("No valid documents to rerank");
        }
        Map<String, Object> request = Map.of(
                "query", query,
                "documents", texts,
                "top_n", topN
        );
        log.info("Sending rerank request: query={}, documents={}", query, documents.stream().map(doc -> doc.getMetadata().get("source")).toList());
        List<Map<String, Object>> response;
        try {
            response = restTemplate.postForObject(rerankerServiceUrl, request, List.class);
        } catch (RestClientException e) {
            log.error("Rerank request failed", e);
            return null;
        }
        log.info("Rerank response: {}", response);
        if (response == null) {
            return List.of();
        } else {
            return response.stream()
                    .sorted((a, b) -> Double.compare(
                            ((Number) b.get("score")).doubleValue(),
                            ((Number) a.get("score")).doubleValue()
                    ))
                    .map(r -> new Result(
                                    documents.get(((Number) r.get("index")).intValue()),
                                    ((Number) r.get("score")).doubleValue()
                            )
                    )
                    .limit(topN)
                    .collect(Collectors.toList());
        }
    }
}