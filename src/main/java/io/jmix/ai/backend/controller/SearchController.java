package io.jmix.ai.backend.controller;

import io.jmix.ai.backend.chat.SearchService;
import jakarta.annotation.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public List<SearchResultDocument> search(@RequestBody SearchRequest request) {
        List<Document> documents = searchService.search(request.query(), request.type());

        return convertToSearchResults(documents);
    }


    private List<SearchResultDocument> convertToSearchResults(List<Document> documents) {
        return documents.stream()
                .map(document -> new SearchResultDocument(
                        UUID.randomUUID().toString(),
                        document.getText(),
                        document.getFormattedContent()))
                .toList();
    }

    public record SearchResultDocument(String id, String title, String content) {
    }

    public record SearchRequest(String query, @Nullable String type) {
    }
}
