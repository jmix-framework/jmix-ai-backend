package io.jmix.ai.backend.controller;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.retrieval.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchControllersTest {

    @Mock
    private SearchService searchService;

    @Test
    void legacyEndpointKeepsOriginalResponseSemantics() {
        Document document = new Document(
                "stored-id", "legacy body", Map.of("source", "docs/page.html"));
        when(searchService.search("query", JmixVersion.V3)).thenReturn(List.of(document));
        SearchController controller = new SearchController(searchService);

        List<SearchController.SearchResultDocument> result = controller.search(
                new SearchController.SearchRequest("query", "v3"));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.id()).isNotEqualTo(document.getId());
            assertThat(UUID.fromString(item.id())).isNotNull();
            assertThat(item.title()).isEqualTo(document.getText());
            assertThat(item.content()).isEqualTo(document.getFormattedContent());
        });
        assertThat(SearchController.SearchResultDocument.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("id", "title", "content");
        verify(searchService).search("query", JmixVersion.V3);
    }

    @Test
    void v2EndpointReturnsContextResponseAndAppliesBestEffortBudget() {
        Document lessRelevant = Document.builder()
                .id("low")
                .text("TITLE: Low result\nDESCRIPTION: low")
                .metadata(Map.of("url", "https://example.com/low"))
                .score(0.2)
                .build();
        Document mostRelevant = Document.builder()
                .id("high")
                .text("TITLE: High result\nDESCRIPTION: high")
                .metadata(Map.of("url", "https://example.com/high"))
                .score(0.9)
                .build();
        // the service returns results already relevance-ordered; the controller must not reorder
        when(searchService.search("query", JmixVersion.V2, null))
                .thenReturn(List.of(mostRelevant, lessRelevant));
        SearchV2Controller controller = new SearchV2Controller(searchService);

        List<SearchV2Controller.SearchResultDocument> result = controller.search(
                new SearchV2Controller.SearchRequest("query", null, 1, null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("high");
            assertThat(item.title()).isEqualTo("High result");
            assertThat(item.source()).isEqualTo("https://example.com/high");
            assertThat(item.content()).isEqualTo(mostRelevant.getText());
        });
        verify(searchService).search("query", JmixVersion.V2, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 100_001})
    void v2EndpointRejectsInvalidTokenBudget(int tokens) {
        SearchV2Controller controller = new SearchV2Controller(searchService);

        assertThatThrownBy(() -> controller.search(
                new SearchV2Controller.SearchRequest("query", "v2", tokens, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(searchService);
    }

    @Test
    void v2EndpointPassesMaxResultsToEveryRetrievalTool() {
        when(searchService.search("query", JmixVersion.V2, 7)).thenReturn(List.of());
        SearchV2Controller controller = new SearchV2Controller(searchService);

        controller.search(new SearchV2Controller.SearchRequest("query", "v2", null, 7));

        verify(searchService).search("query", JmixVersion.V2, 7);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 51})
    void v2EndpointRejectsInvalidMaxResults(int maxResults) {
        SearchV2Controller controller = new SearchV2Controller(searchService);

        assertThatThrownBy(() -> controller.search(
                new SearchV2Controller.SearchRequest("query", "v2", null, maxResults)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(searchService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v4", "2.8", "current"})
    void v2EndpointRejectsUnknownJmixVersion(String version) {
        SearchV2Controller controller = new SearchV2Controller(searchService);

        assertThatThrownBy(() -> controller.search(
                new SearchV2Controller.SearchRequest("query", version, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(searchService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void v2EndpointRejectsBlankQuery(String query) {
        SearchV2Controller controller = new SearchV2Controller(searchService);

        assertThatThrownBy(() -> controller.search(
                new SearchV2Controller.SearchRequest(query, "v2", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(searchService);
    }

    @Test
    void v2EndpointRejectsQueryLongerThanTenThousandCharacters() {
        SearchV2Controller controller = new SearchV2Controller(searchService);

        assertThatThrownBy(() -> controller.search(
                new SearchV2Controller.SearchRequest("x".repeat(10_001), "v2", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(searchService);
    }
}
