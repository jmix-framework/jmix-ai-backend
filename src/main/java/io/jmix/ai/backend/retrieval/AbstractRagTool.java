package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

import io.jmix.ai.backend.chat.EventStreamValueHolder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The {@code topK} parameter selects between two pipelines. A number pins the fixed pipeline:
 * exactly {@code topK} candidates are fetched, the reranker keeps {@code topReranked}, and the
 * caller cannot influence the result count — the behavior existing configurations were tuned
 * for. An explicit {@code topK: null} (or an absent key) enables the adaptive pipeline: the
 * model or the search API may request how many snippets it needs, and the candidate pool is
 * sized from that request.
 */
public abstract class AbstractRagTool {

    /** Upper bound on how many snippets a caller (the model or the search API) may request per tool call. */
    public static final int MAX_RESULTS_CAP = 50;
    /** Upper bound on the vector-store fetch in the adaptive pipeline, whatever was requested. */
    static final int MAX_VECTOR_FETCH = 120;
    // A page's snippets are many and small, so a topically-close page can flood the whole
    // candidate pool by itself (cosine search is blind to pages). The candidates are fetched
    // with a margin and capped per source page, so the reranker always sees several distinct
    // pages to choose from.
    static final int MAX_CHUNKS_PER_SOURCE = 3;
    private static final String MAX_RESULTS_DESCRIPTION =
            "Optional: how many snippets to return (1-" + MAX_RESULTS_CAP
                    + "). Omit to use the configured default. The tool description states the "
                    + "approximate token size of one snippet.";

    protected final String toolName;
    protected final VectorStore vectorStore;
    private final PostRetrievalProcessor postRetrievalProcessor;
    private final Reranker reranker;
    private final List<Document> retrievedDocuments;
    private final ToolEventListener listener;
    private final ParametersReader parametersReader;
    protected String type;
    protected final JmixVersion jmixVersion;
    private final boolean versionScoped;
    protected String description;
    protected double similarityThreshold;
    @Nullable
    protected Integer topK;
    protected int topReranked;
    private double minScore;
    private double minRerankedScore;
    private String noResultsMessage;

    protected AbstractRagTool(String toolName, String type, VectorStore vectorStore,
                              PostRetrievalProcessor postRetrievalProcessor, Reranker reranker,
                              ParametersReader parametersReader, List<Document> retrievedDocuments,
                              ToolEventListener listener, JmixVersion jmixVersion, boolean versionScoped) {
        this.toolName = toolName;
        this.vectorStore = vectorStore;
        this.postRetrievalProcessor = postRetrievalProcessor;
        this.reranker = reranker;
        this.retrievedDocuments = retrievedDocuments;
        this.listener = listener;
        this.parametersReader = parametersReader;
        this.type = type;
        this.jmixVersion = Objects.requireNonNull(jmixVersion, "jmixVersion must not be null");
        this.versionScoped = versionScoped;
        init(parametersReader);
    }
    protected String getToolRootKey() {
        return "tools." + toolName;
    }

    protected void init(ParametersReader parametersReader) {
        // allows A/B testing an alternative corpus (e.g. docs-snippets) with the same tool
        type = parametersReader.getString(getToolRootKey() + ".vectorType", type);
        description = parametersReader.getString(getToolRootKey() + ".description");
        Integer averageDocumentTokens = parametersReader.getInteger(
                getToolRootKey() + ".averageDocumentTokens", null);
        if (averageDocumentTokens != null && averageDocumentTokens > 0) {
            description += " A typical returned snippet is about %d tokens."
                    .formatted(averageDocumentTokens);
        }
        similarityThreshold = parametersReader.getDouble(getToolRootKey() + ".similarityThreshold");
        topK = parametersReader.getInteger(getToolRootKey() + ".topK", null);
        topReranked = parametersReader.getInt(getToolRootKey() + ".topReranked");
        minScore = parametersReader.getDouble(getToolRootKey() + ".minScore");
        minRerankedScore = parametersReader.getDouble(getToolRootKey() + ".minRerankedScore");
        noResultsMessage = parametersReader.getString(getToolRootKey() + ".noResultsMessage", "No results found for the query. Try rephrasing your query or using another tool.");
    }

    private boolean isFixedPipeline() {
        return topK != null;
    }

    public ToolCallback getToolCallback() {
        // the exposed method defines the LLM-visible schema: the fixed pipeline accepts only the
        // query, the adaptive one also lets the model request a result count
        Method method = Objects.requireNonNull(isFixedPipeline()
                ? ReflectionUtils.findMethod(getClass(), "execute", String.class)
                : ReflectionUtils.findMethod(getClass(), "execute", String.class, Integer.class));

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

    /** Fixed-pipeline entry point: retrieval sizes come from the configuration alone. */
    public String execute(String queryText) {
        if (!isFixedPipeline()) {
            // an adaptive tool has no topK to unbox; a one-arg call means "no requested count"
            return execute(queryText, null);
        }
        return executeSearch(queryText, topK, topReranked, null, null, null);
    }

    /**
     * Adaptive entry point: {@code maxResults} lets the caller decide how many snippets it needs
     * back for this call; omit it to use the configured default. On a fixed-pipeline tool
     * {@code maxResults} is ignored.
     */
    public String execute(
            @ToolParam(description = "Search query in English. For API questions use the class or member name (e.g. DataManager, FetchPlan.BASE); otherwise a short natural-language query.")
            String queryText,
            @ToolParam(required = false, description = MAX_RESULTS_DESCRIPTION)
            Integer maxResults) {
        if (isFixedPipeline()) {
            return execute(queryText);
        }
        boolean callerRequested = maxResults != null && maxResults > 0;
        int requested = callerRequested ? Math.min(maxResults, MAX_RESULTS_CAP) : topReranked;
        // overfetch: the per-source cap needs spare candidates to refill from. The reranker is
        // asked for the whole pool — it scores every candidate in one call anyway, and truncating
        // its result before the cap would leave nothing to refill from when one page floods the top
        int vectorFetch = Math.min(requested * 4, MAX_VECTOR_FETCH);
        return executeSearch(queryText, vectorFetch, vectorFetch, requested, requested,
                callerRequested ? new EventStreamValueHolder.RequestedRetrieval(requested, vectorFetch) : null);
    }

    /**
     * Runs the retrieval pipeline with every knob explicit: fetch exactly {@code vectorTopK}
     * candidates, ask the reranker for {@code rerankTopN}. Null {@code resultLimit} and
     * {@code fallbackLimit} select the fixed-pipeline semantics — no per-source cap and an
     * unbounded minScore filter when reranking fails; non-null values cap both paths.
     */
    private String executeSearch(
            String queryText,
            int vectorTopK,
            int rerankTopN,
            @Nullable Integer resultLimit,
            @Nullable Integer fallbackLimit,
            @Nullable EventStreamValueHolder.RequestedRetrieval requested) {
        long startTime = System.currentTimeMillis();
        listener.onToolCallStart(toolName, queryText, requested);

        try {
            // Retrieval
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(queryText)
                    .similarityThreshold(similarityThreshold)
                    .topK(vectorTopK);

            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            var typeFilter = fb.eq("type", type);
            var filter = versionScoped
                    ? fb.and(typeFilter, fb.eq("jmixVersion", jmixVersion.getId())).build()
                    : typeFilter.build();
            requestBuilder.filterExpression(filter);

            SearchRequest searchRequest = requestBuilder.build();

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

            // Reranking. The reranker judges every candidate: capping per source beforehand would
            // hide relevant chunks from it by raw cosine alone — the page that legitimately holds
            // most of the answer loses its less obvious parts (a job page keeps its "how to
            // schedule" snippets and drops the "authenticate the job" one). The cap is applied to
            // the reranked list instead, where dropped chunks can be replaced by the next best
            // ones the reranker already scored.
            List<Document> filteredDocuments;

            long rerankStart = System.currentTimeMillis();
            List<Reranker.Result> rerankResults =
                    reranker.rerank(queryText, documents, rerankTopN, parametersReader);
            long rerankMs = System.currentTimeMillis() - rerankStart;

            if (rerankResults == null) {
                listener.onLog("Reranking failed, filtering by minScore");
                List<Document> minScoreFiltered = documents.stream()
                        .filter(document ->
                                minScore <= 0.0 || document.getScore() == null || document.getScore() >= minScore)
                        .toList();
                filteredDocuments = fallbackLimit == null
                        ? minScoreFiltered
                        : capPerSource(minScoreFiltered, fallbackLimit);
                listener.onToolReranked(toolName, toDocScores(filteredDocuments), rerankMs);
            } else {
                List<Reranker.Result> filteredRerankResults = rerankResults.stream()
                        .filter(rr -> rr.score() >= minRerankedScore)
                        .toList();

                for (Reranker.Result result : filteredRerankResults) {
                    result.document().getMetadata().put("rerankScore", result.score());
                }

                List<Document> rerankedDocuments = filteredRerankResults.stream()
                        .map(Reranker.Result::document)
                        .toList();
                filteredDocuments = resultLimit == null
                        ? rerankedDocuments
                        : capPerSource(rerankedDocuments, resultLimit);
                List<Document> selected = filteredDocuments;
                listener.onToolReranked(toolName,
                        filteredRerankResults.stream()
                                .filter(rr -> selected.contains(rr.document()))
                                .map(rr -> new EventStreamValueHolder.DocScore(rr.score(), RetrievalUtils.getUrlOrSource(rr.document())))
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

    /**
     * Keeps at most {@link #MAX_CHUNKS_PER_SOURCE} best-ranked chunks per source page and trims
     * the list back to {@code limit}, preserving the similarity order. Documents without a
     * {@code source} are never capped.
     */
    private List<Document> capPerSource(List<Document> documents, int limit) {
        Map<Object, Integer> chunksPerSource = new HashMap<>();
        List<Document> capped = new ArrayList<>(Math.min(documents.size(), limit));
        int flooded = 0;
        for (Document document : documents) {
            Object source = document.getMetadata().getOrDefault("source", document.getId());
            if (chunksPerSource.merge(source, 1, Integer::sum) > MAX_CHUNKS_PER_SOURCE) {
                flooded++;
                continue;
            }
            capped.add(document);
            if (capped.size() == limit) {
                break;
            }
        }
        if (flooded > 0) {
            listener.onLog("Per-source cap dropped %d flooded chunks".formatted(flooded));
        }
        return capped;
    }

    private static List<EventStreamValueHolder.DocScore> toDocScores(List<Document> documents) {
        return documents.stream()
                .map(doc -> new EventStreamValueHolder.DocScore(
                        doc.getScore() != null ? doc.getScore() : 0.0,
                        RetrievalUtils.getUrlOrSource(doc)))
                .toList();
    }

    protected String getNoResultsMessage() {
        return noResultsMessage;
    }
}
