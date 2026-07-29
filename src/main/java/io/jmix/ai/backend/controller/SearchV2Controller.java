package io.jmix.ai.backend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.retrieval.AbstractRagTool;
import io.jmix.ai.backend.retrieval.SearchResultsFormatter;
import io.jmix.ai.backend.retrieval.SearchService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v2/search")
public class SearchV2Controller {

    private static final int MAX_QUERY_LENGTH = 10_000;
    private static final int MAX_TOKEN_BUDGET = 100_000;

    private final SearchService searchService;

    public SearchV2Controller(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public List<SearchResultDocument> search(@RequestBody SearchRequest request) {
        if (StringUtils.isBlank(request.query())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query is empty or blank");
        }
        if (request.query().length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query is too long");
        }
        if (request.tokens() != null
                && (request.tokens() <= 0 || request.tokens() > MAX_TOKEN_BUDGET)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tokens must be between 1 and " + MAX_TOKEN_BUDGET);
        }
        if (request.maxResults() != null
                && (request.maxResults() <= 0 || request.maxResults() > AbstractRagTool.MAX_RESULTS_CAP)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Max results must be between 1 and " + AbstractRagTool.MAX_RESULTS_CAP);
        }

        JmixVersion version = JmixVersion.fromId(request.jmixVersion());
        if (version == null && StringUtils.isNotBlank(request.jmixVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown Jmix version: " + request.jmixVersion());
        }
        if (version == null) {
            // v2 clients are frozen on SearchController and its v2 default; a caller that opted into
            // this endpoint without naming a version gets the current Jmix release
            version = JmixVersion.V3;
        }
        List<Document> sortedByRelevance = searchService.search(request.query(), version, request.maxResults());
        List<Document> trimmedToBudget = SearchResultsFormatter.applyTokenBudget(sortedByRelevance, request.tokens());

        return trimmedToBudget.stream()
                .map(document -> {
                    String title = SearchResultsFormatter.extractTitle(document);
                    String source = SearchResultsFormatter.extractSource(document);
                    return new SearchResultDocument(document.getId(), title, source, document.getText());
                })
                .toList();
    }

    public record SearchResultDocument(String id, String title, String source, String content) {
    }

    /**
     * {@code maxResults} caps the total number of returned snippets across all tools, so the
     * caller (e.g. an MCP client's LLM) decides how much context it wants without knowing how many
     * tools the server has enabled; {@code tokens} then trims the relevance-ordered total to an
     * approximate budget.
     */
    public record SearchRequest(
            String query,
            @JsonProperty("jmix_version") String jmixVersion,
            Integer tokens,
            @JsonProperty("max_results") Integer maxResults) {
    }
}
