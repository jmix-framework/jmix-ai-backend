package io.jmix.ai.backend.controller;

import io.jmix.ai.backend.retrieval.SearchResultsFormatter;
import io.jmix.ai.backend.retrieval.SearchService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public List<SearchResultDocument> search(@RequestBody SearchRequest request) {
        List<Document> documents = searchService.search(request.query());

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
            Integer tokens) {
    }
}
