package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SearchService {
    private final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final ParametersRepository parametersRepository;
    private final ToolsManager toolsManager;

    public SearchService(ParametersRepository parametersRepository,
                         ToolsManager toolsManager) {
        this.parametersRepository = parametersRepository;
        this.toolsManager = toolsManager;
    }

    public List<Document> search(String query, JmixVersion jmixVersion) {
        return search(query, jmixVersion, null);
    }

    /**
     * Runs the retrieval tools without the answering LLM. {@code maxResults} caps the total number
     * of returned snippets across all tools (the caller decides how much context it needs), not the
     * count per tool: adaptive tools ({@code topK: null}) each contribute up to {@code maxResults}
     * candidates, while fixed-pipeline tools (a configured {@code topK}) ignore it and contribute
     * their configured {@code topReranked} (or every minScore-passing candidate when reranking
     * fails). The pool is merged, ordered by relevance and trimmed to {@code maxResults} globally,
     * so the response size honors the cap in any mix of modes. Null uses the configured per-tool
     * defaults with no overall cap. The number of enabled tools is a server-side detail that must
     * not leak into the size of the response.
     */
    public List<Document> search(String query, JmixVersion jmixVersion, @Nullable Integer maxResults) {
        List<Document> retrievedDocuments = new ArrayList<>();

        Parameters parameters = parametersRepository.loadActive(ParametersTargetType.SEARCH);

        List<AbstractRagTool> ragTools = toolsManager.getTools(parameters.getContent(), jmixVersion);

        // each tool fills the pool with up to maxResults candidates from its own corpus
        for (AbstractRagTool tool : ragTools) {
            RetrievalResult result = tool.search(query, maxResults);
            logResult(result);
            retrievedDocuments.addAll(result.documents());
        }

        // then keep the globally most relevant maxResults across all corpora
        List<Document> ranked = RetrievalUtils.getUniqueSortedDocuments(retrievedDocuments);
        if (maxResults != null && ranked.size() > maxResults) {
            return ranked.subList(0, maxResults);
        }
        return ranked;
    }

    private void logResult(RetrievalResult result) {
        logger.debug("Using {}: {}{}", result.tool(), result.query(), RetrievalUtils.formatRequested(result.requested()));
        for (RetrievalResult.Step step : result.steps()) {
            switch (step) {
                case RetrievalResult.Retrieved retrieved ->
                        logger.debug("Retrieved {} docs in {} ms", retrieved.documents().size(), retrieved.durationMs());
                case RetrievalResult.Reranked reranked ->
                        logger.debug("Reranked to {} docs in {} ms", reranked.documents().size(), reranked.durationMs());
                case RetrievalResult.Note note -> logger.debug(note.message());
            }
        }
        logger.debug("{} done in {} ms", result.tool(), result.durationMs());
    }
}
