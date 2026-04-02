package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.util.ReflectionUtils;

import io.jmix.ai.backend.chat.EventStreamValueHolder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.jmix.ai.backend.retrieval.Utils.getUrlOrSource;

public abstract class AbstractRagTool {

    protected final String toolName;
    protected final VectorStore vectorStore;
    private final PostRetrievalProcessor postRetrievalProcessor;
    private final Reranker reranker;
    private final List<Document> retrievedDocuments;
    private final ToolEventListener listener;
    private final ParametersReader parametersReader;
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
                              ParametersReader parametersReader, List<Document> retrievedDocuments,
                              ToolEventListener listener) {
        this.toolName = toolName;
        this.vectorStore = vectorStore;
        this.postRetrievalProcessor = postRetrievalProcessor;
        this.reranker = reranker;
        this.retrievedDocuments = retrievedDocuments;
        this.listener = listener;
        this.parametersReader = parametersReader;
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
        long startTime = System.currentTimeMillis();
        listener.onToolCallStart(toolName, queryText);

        try {
            // Retrieval
            SearchRequest searchRequest = SearchRequest.builder()
                    .filterExpression(new FilterExpressionBuilder().eq("type", type).build())
                    .query(queryText)
                    .similarityThreshold(similarityThreshold)
                    .topK(topK)
                    .build();

            long retrievalStart = System.currentTimeMillis();
            List<Document> documents = vectorStore.similaritySearch(searchRequest);
            long retrievalMs = System.currentTimeMillis() - retrievalStart;

            if (documents == null) {
                listener.onToolRetrieved(toolName, List.of(), retrievalMs);
                return getNoResultsMessage();
            }
            listener.onToolRetrieved(toolName, toDocScores(documents), retrievalMs);

            documents = postRetrievalProcessor.process(queryText, documents);
            if (documents.isEmpty()) {
                listener.onLog("All documents filtered out by PostRetrievalProcessor");
                return getNoResultsMessage();
            }

            // Reranking
            List<Document> filteredDocuments;

            long rerankStart = System.currentTimeMillis();
            List<Reranker.Result> rerankResults = reranker.rerank(queryText, documents, topReranked, parametersReader);
            long rerankMs = System.currentTimeMillis() - rerankStart;

            if (rerankResults == null) {
                listener.onLog("Reranking failed, filtering by minScore");
                filteredDocuments = documents.stream()
                        .filter(document ->
                                minScore <= 0.0 || document.getScore() == null || document.getScore() >= minScore)
                        .toList();
                listener.onToolReranked(toolName, toDocScores(filteredDocuments), rerankMs);
            } else {
                List<Reranker.Result> filteredRerankResults = rerankResults.stream()
                        .filter(rr -> rr.score() >= minRerankedScore)
                        .toList();

                for (Reranker.Result result : filteredRerankResults) {
                    result.document().getMetadata().put("rerankScore", result.score());
                }

                filteredDocuments = filteredRerankResults.stream()
                        .map(Reranker.Result::document)
                        .toList();
                listener.onToolReranked(toolName,
                        filteredRerankResults.stream()
                                .map(rr -> new EventStreamValueHolder.DocScore(rr.score(), getUrlOrSource(rr.document())))
                                .toList(),
                        rerankMs);
            }

            if (filteredDocuments.isEmpty()) {
                return getNoResultsMessage();
            }

            retrievedDocuments.addAll(filteredDocuments);

            return filteredDocuments.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n"));
        } finally {
            listener.onToolCallEnd(toolName, System.currentTimeMillis() - startTime);
        }
    }

    private static List<EventStreamValueHolder.DocScore> toDocScores(List<Document> documents) {
        return documents.stream()
                .map(doc -> new EventStreamValueHolder.DocScore(
                        doc.getScore() != null ? doc.getScore() : 0.0,
                        getUrlOrSource(doc)))
                .toList();
    }

    protected String getNoResultsMessage() {
        return noResultsMessage;
    }
}
