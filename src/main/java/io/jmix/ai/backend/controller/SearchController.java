package io.jmix.ai.backend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.jmix.ai.backend.entity.JmixVersion;
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
@RequestMapping("/api/search")
public class SearchController {

    private static final int MAX_QUERY_LENGTH = 10_000;

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
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
        JmixVersion version = JmixVersion.fromId(request.jmixVersion());
        if (version == null) {
            version = JmixVersion.V2;
        }
        List<Document> documents = searchService.search(request.query(), version);

        documents = SearchResultsFormatter.sortByRelevance(documents);
        documents = SearchResultsFormatter.applyTokenBudget(documents, request.tokens());

        return convertToSearchResults(documents);
    }

    private List<SearchResultDocument> convertToSearchResults(List<Document> documents) {
        return documents.stream()
                .map(document -> new SearchResultDocument(
                        document.getId(),
                        SearchResultsFormatter.extractTitle(document),
                        SearchResultsFormatter.extractSource(document),
                        document.getText()))
                .toList();
    }

    public record SearchResultDocument(String id, String title, String source, String content) {
    }

    public record SearchRequest(
            String query,
            @JsonProperty("jmix_version") String jmixVersion,
            Integer tokens) {
    }
}
