package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

import static io.jmix.ai.backend.chat.Utils.getDocSourcesAsString;
import static io.jmix.ai.backend.chat.Utils.getRerankResultsAsString;
import static org.apache.commons.lang3.StringUtils.abbreviate;

@Component
public class InitialRetriever {

    public final VectorStore vectorStore;
    public final Reranker reranker;

    public InitialRetriever(VectorStore vectorStore, Reranker reranker) {
        this.vectorStore = vectorStore;
        this.reranker = reranker;
    }

    List<Document> retrieve(String queryText, ParametersReader parametersReader, Consumer<String> logger) {
        double similarityThreshold = parametersReader.getDouble("initialRetriever.similarityThreshold");
        int topK = parametersReader.getInt("initialRetriever.topK");
        int topReranked = parametersReader.getInt("initialRetriever.topReranked");
        double minRerankedScore = parametersReader.getDouble("initialRetriever.minRerankedScore");

        logger.accept("Using initialRetriever [%.2f, %d, %d, %.2f]: %s".formatted(
                similarityThreshold, topK, topReranked, minRerankedScore, abbreviate(queryText, 200)));

        SearchRequest searchRequest = SearchRequest.builder()
                .query(queryText)
                .similarityThreshold(similarityThreshold)
                .topK(topK)
                .build();
        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        if (documents == null || documents.isEmpty()) {
            logger.accept("No documents found for the query");
            return List.of();
        }

        logger.accept("Found documents (%d): %s".formatted(documents.size(), getDocSourcesAsString(documents)));

        List<Document> filteredDocuments;

        List<Reranker.Result> rerankResults = reranker.rerank(queryText, documents, topReranked);

        if (rerankResults == null) {
            logger.accept("Reranking failed, filtering by minScore");
            double minScore = parametersReader.getDouble("initialRetriever.minScore");
            filteredDocuments = documents.stream()
                    .filter(document ->
                            minScore <= 0.0 || document.getScore() == null || document.getScore() >= minScore)
                    .toList();
            logger.accept("Filtered documents (%d): %s".formatted(filteredDocuments.size(), getDocSourcesAsString(filteredDocuments)));

        } else {
            List<Reranker.Result> filteredRerankResults = rerankResults.stream()
                    .filter(rr -> rr.score() >= minRerankedScore)
                    .toList();
            logger.accept("Reranked documents (%d): %s".formatted(filteredRerankResults.size(), getRerankResultsAsString(filteredRerankResults)));

            for (Reranker.Result result : filteredRerankResults) {
                result.document().getMetadata().put("rerankScore", result.score());
            }

            filteredDocuments = filteredRerankResults.stream()
                    .map(Reranker.Result::document)
                    .toList();
        }

        if (filteredDocuments.isEmpty()) {
            return List.of();
        }

        return filteredDocuments;
    }
}
