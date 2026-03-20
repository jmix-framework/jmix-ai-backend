package io.jmix.ai.backend.retrieval;

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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.jmix.ai.backend.retrieval.Utils.getDocSourcesAsString;
import static io.jmix.ai.backend.retrieval.Utils.getUrlOrSource;
import static io.jmix.ai.backend.retrieval.Utils.getRerankResultsAsString;

public abstract class AbstractRagTool {
    private static final Pattern EXACT_IDENTIFIER_PATTERN = Pattern.compile(
            "(?iu)(" +
                    "@[a-z_][a-z0-9_]*|" +
                    "\\b(beforeActionPerformedHandler|afterSaveHandler|loadDelegate|saveDelegate|removeDelegate|totalCountDelegate|optionCaptionProvider|valueProvider)\\b|" +
                    "\\b[a-z][a-z0-9]*?(delegate|provider|handler|listener|formatter|validator)\\b" +
                    ")"
    );

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

    public String getToolName() {
        return toolName;
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
            List<Reranker.Result> filteredRerankResults = applyRerankThreshold(
                    rerankResults,
                    minRerankedScore,
                    shouldFallbackToTopReranked(),
                    getRerankFallbackLimit()
            );
            filteredRerankResults = rescueExactIdentifierMatches(queryText, filteredRerankResults, rerankResults);
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

    protected boolean shouldFallbackToTopReranked() {
        return false;
    }

    protected int getRerankFallbackLimit() {
        return 0;
    }

    static List<Reranker.Result> applyRerankThreshold(List<Reranker.Result> rerankResults, double minRerankedScore,
                                                      boolean fallbackToTopReranked, int fallbackLimit) {
        List<Reranker.Result> filtered = rerankResults.stream()
                .filter(rr -> rr.score() >= minRerankedScore)
                .toList();
        if (!filtered.isEmpty() || !fallbackToTopReranked || fallbackLimit <= 0) {
            return filtered;
        }

        List<Reranker.Result> fallback = new ArrayList<>();
        for (Reranker.Result result : rerankResults) {
            fallback.add(result);
            if (fallback.size() >= fallbackLimit) {
                break;
            }
        }
        return fallback;
    }

    private List<Reranker.Result> rescueExactIdentifierMatches(String queryText,
                                                               List<Reranker.Result> filteredRerankResults,
                                                               List<Reranker.Result> rerankResults) {
        if (!filteredRerankResults.isEmpty()) {
            return filteredRerankResults;
        }

        List<String> exactIdentifiers = extractExactIdentifiers(queryText);
        if (exactIdentifiers.isEmpty()) {
            return filteredRerankResults;
        }

        List<Reranker.Result> rescued = rerankResults.stream()
                .filter(result -> containsExactIdentifier(result.document(), exactIdentifiers))
                .toList();
        if (!rescued.isEmpty()) {
            logger.accept("Rescued reranked documents by exact identifier match %s: %s"
                    .formatted(exactIdentifiers, getRerankResultsAsString(rescued)));
            return rescued;
        }
        return filteredRerankResults;
    }

    private static List<String> extractExactIdentifiers(String queryText) {
        if (StringUtils.isBlank(queryText)) {
            return List.of();
        }

        Matcher matcher = EXACT_IDENTIFIER_PATTERN.matcher(queryText);
        Set<String> identifiers = new LinkedHashSet<>();
        while (matcher.find()) {
            String raw = matcher.group();
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            identifiers.add(raw);
            if (raw.startsWith("@") && raw.length() > 1) {
                identifiers.add(raw.substring(1));
            }
        }
        return List.copyOf(identifiers);
    }

    private static boolean containsExactIdentifier(Document document, List<String> exactIdentifiers) {
        String source = getUrlOrSource(document);
        String text = document.getText();
        String searchable = ((source != null ? source : "") + "\n" + (text != null ? text : "")).toLowerCase();
        for (String identifier : exactIdentifiers) {
            if (searchable.contains(identifier.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

}
