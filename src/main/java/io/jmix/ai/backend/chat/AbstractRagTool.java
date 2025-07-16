package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.jmix.ai.backend.chat.Utils.getDocSourcesAsString;
import static io.jmix.ai.backend.chat.Utils.getRerankResultsAsString;

public abstract class AbstractRagTool {

    protected final String toolName;
    protected final VectorStore vectorStore;
    private final PostRetrievalProcessor postRetrievalProcessor;
    private final Reranker reranker;
    private final List<Document> retrievedDocuments;
    private final Consumer<String> logger;
    protected final String type;
    protected String description;
    protected double similarityThreshold;
    protected int topK;
    protected int topReranked;
    private double minScore;
    private double minRerankedScore;
    private String noResultsMessage;

    protected AbstractRagTool(String toolName, String type, VectorStore vectorStore,
                              PostRetrievalProcessor postRetrievalProcessor, Reranker reranker,
                              ParametersReader parametersReader, List<Document> retrievedDocuments, Consumer<String> logger) {
        this.toolName = toolName;
        this.vectorStore = vectorStore;
        this.postRetrievalProcessor = postRetrievalProcessor;
        this.reranker = reranker;
        this.retrievedDocuments = retrievedDocuments;
        this.logger = logger;
        this.type = type;
        init(parametersReader);
    }

    protected String getToolRootKey() {
        return "tools." + toolName;
    }

    protected void init(ParametersReader parametersReader) {
        description = parametersReader.getString(getToolRootKey() + ".description");
        similarityThreshold = parametersReader.getDouble(getToolRootKey() + ".similarityThreshold");
        topK = parametersReader.getInt(getToolRootKey() + ".topK");
        topReranked = parametersReader.getInt(getToolRootKey() + ".topReranked");
        minScore = parametersReader.getDouble(getToolRootKey() + ".minScore");
        minRerankedScore = parametersReader.getDouble(getToolRootKey() + ".minRerankedScore");
        noResultsMessage = parametersReader.getString(getToolRootKey() + ".noResultsMessage", "No results found for the query. Try rephrasing your query or using another tool.");
    }

    public ToolCallback getToolCallback() {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(getClass(), "execute", String.class));

        ToolCallback toolCallback = MethodToolCallback.builder()
                .toolDefinition(ToolDefinition.builder()
                        .name(toolName)
                        .description(description)
                        .inputSchema(JsonSchemaGenerator.generateForMethodInput(method))
                        .build())
                .toolObject(this)
                .toolMethod(method)
                .build();
        return toolCallback;
    }

    public String execute(String queryText) {
        return executeSearch(queryText, similarityThreshold, topK);
    }

    protected String executeSearch(String queryText, double similarityThreshold, int topK) {
        logger.accept(">>> Using %s ['%s', %.2f, %d, %.2f]: %s".formatted(
                toolName, StringUtils.abbreviate(description, 10), similarityThreshold, topK, minScore, queryText));

        SearchRequest searchRequest = SearchRequest.builder()
                .filterExpression(new FilterExpressionBuilder().eq("type", type).build())
                .query(queryText)
                .similarityThreshold(similarityThreshold)
                .topK(topK)
                .build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        if (documents == null) {
            logger.accept("No documents found for the query");
            return getNoResultsMessage();
        }
        logger.accept("Found documents (%d): %s".formatted(documents.size(), getDocSourcesAsString(documents)));

        documents = postRetrievalProcessor.process(queryText, documents);
        if (documents.isEmpty()) {
            logger.accept("All documents filtered out by PostRetrievalProcessor");
            return getNoResultsMessage();
        }

        List<Document> filteredDocuments;

        List<Reranker.Result> rerankResults = reranker.rerank(queryText, documents, topReranked);

        if (rerankResults == null) {
            logger.accept("Reranking failed, filtering by minScore");
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
            return getNoResultsMessage();
        }

        retrievedDocuments.addAll(filteredDocuments);

        return filteredDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    protected String getNoResultsMessage() {
        return noResultsMessage;
    };


}