package io.jmix.ai.backend.chat;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class AbstractRagTool {

    protected final VectorStore vectorStore;
    private final Consumer<String> logger;
    protected final String type;
    protected final String metadataFieldForLogging;
    protected final String description;
    protected final Double similarityThreshold;
    protected final Integer topK;


    protected AbstractRagTool(VectorStore vectorStore, Consumer<String> logger, String type, String metadataFieldForLogging,
                              String description, Double similarityThreshold, Integer topK) {
        this.vectorStore = vectorStore;
        this.logger = logger;
        this.type = type;
        this.metadataFieldForLogging = metadataFieldForLogging;
        this.description = description;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
    }

    protected String executeSearch(String queryText, double similarityThreshold, int topK) {
        logger.accept("Using %s tool ['%s', %.2f, %d]: %s".formatted(
                this.getClass().getSimpleName(), StringUtils.abbreviate(description, 10), similarityThreshold, topK, queryText));

        SearchRequest searchRequest = SearchRequest.builder()
                .filterExpression(new FilterExpressionBuilder().eq("type", type).build())
                .query(queryText)
                .similarityThreshold(similarityThreshold)
                .topK(topK)
                .build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        
        if (documents == null) {
            logger.accept("No documents found for the query");
        } else {
            List<String> links = documents.stream()
                    .map(document ->
                            "(" + String.format("%.3f", document.getScore()) + ") " +
                                    document.getMetadata().get(metadataFieldForLogging))
                    .toList();
            logger.accept("Found %d documents: %s".formatted(links.size(), links));
        }

        if (documents == null || documents.isEmpty()) {
            return getNoResultsMessage();
        }
        
        return documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    public abstract ToolCallback getToolCallback();

    /**
     * Get the message to display when no results are found
     */
    protected abstract String getNoResultsMessage();
}