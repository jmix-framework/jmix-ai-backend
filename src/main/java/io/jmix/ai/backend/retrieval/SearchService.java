package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.chat.EventStreamValueHolder;
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
     * count per tool: each tool contributes up to {@code maxResults} candidates, they are merged and
     * ordered by relevance, and the head is kept. Null uses the configured per-tool defaults with no
     * overall cap. The number of enabled tools is a server-side detail that must not leak into the
     * size of the response.
     */
    public List<Document> search(String query, JmixVersion jmixVersion, @Nullable Integer maxResults) {
        List<Document> retrievedDocuments = new ArrayList<>();

        List<String> logMessages = new ArrayList<>();

        ToolEventListener listener = new ToolEventListener() {
            @Override
            public void onToolCallStart(String tool, String query,
                                        @Nullable EventStreamValueHolder.RequestedRetrieval requested) {
                String suffix = requested == null ? ""
                        : " (%d results requested, vector fetch widened to %d)"
                                .formatted(requested.results(), requested.vectorFetch());
                RetrievalUtils.addLogMessage(logger, logMessages, "Using %s: %s%s".formatted(tool, query, suffix));
            }

            @Override
            public void onToolRetrieved(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                RetrievalUtils.addLogMessage(logger, logMessages, "Retrieved %d docs in %d ms".formatted(documents.size(), durationMs));
            }

            @Override
            public void onToolReranked(String tool, List<EventStreamValueHolder.DocScore> documents, long durationMs) {
                RetrievalUtils.addLogMessage(logger, logMessages, "Reranked to %d docs in %d ms".formatted(documents.size(), durationMs));
            }

            @Override
            public void onToolCallEnd(String tool, long totalDurationMs) {
                RetrievalUtils.addLogMessage(logger, logMessages, "%s done in %d ms".formatted(tool, totalDurationMs));
            }

            @Override
            public void onLog(String message) {
                RetrievalUtils.addLogMessage(logger, logMessages, message);
            }
        };

        Parameters parameters = parametersRepository.loadActive(ParametersTargetType.SEARCH);

        List<AbstractRagTool> ragTools = toolsManager.getTools(parameters.getContent(), retrievedDocuments, listener, jmixVersion);

        // each tool fills the pool with up to maxResults candidates from its own corpus
        for (AbstractRagTool tool : ragTools) {
            tool.execute(query, maxResults);
        }

        // then keep the globally most relevant maxResults across all corpora
        List<Document> ranked = RetrievalUtils.getUniqueSortedDocuments(retrievedDocuments);
        if (maxResults != null && ranked.size() > maxResults) {
            return ranked.subList(0, maxResults);
        }
        return ranked;
    }
}
