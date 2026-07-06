package io.jmix.ai.backend.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultsFormatterTest {

    private static Document doc(String id, String text, Map<String, Object> metadata) {
        return new Document(id, text, new HashMap<>(metadata));
    }

    @Test
    void sortsByRerankScoreThenSimilarity() {
        Document reranked = doc("1", "a", Map.of("rerankScore", 0.7));
        Document rerankedHigher = doc("2", "b", Map.of("rerankScore", 0.9));
        Document similarityOnly = Document.builder().id("3").text("c").score(0.95).build();

        List<Document> sorted = SearchResultsFormatter.sortByRelevance(
                List.of(reranked, similarityOnly, rerankedHigher));

        assertThat(sorted).extracting(Document::getId).containsExactly("3", "2", "1");
    }

    @Test
    void tokenBudgetKeepsHeadAndAtLeastOneDocument() {
        Document big = doc("1", "x".repeat(4000), Map.of());
        Document second = doc("2", "y".repeat(4000), Map.of());

        assertThat(SearchResultsFormatter.applyTokenBudget(List.of(big, second), 1500))
                .extracting(Document::getId).containsExactly("1");
        assertThat(SearchResultsFormatter.applyTokenBudget(List.of(big, second), 100))
                .extracting(Document::getId).containsExactly("1");
        assertThat(SearchResultsFormatter.applyTokenBudget(List.of(big, second), 2000))
                .extracting(Document::getId).containsExactly("1", "2");
        assertThat(SearchResultsFormatter.applyTokenBudget(List.of(big, second), null))
                .hasSize(2);
    }

    @Test
    void extractsTitleFromSnippetText() {
        Document snippet = doc("1", "Path: Flow UI > Button\n\nTITLE: Create a Button\nDESCRIPTION: d\nSOURCE: s", Map.of());
        assertThat(SearchResultsFormatter.extractTitle(snippet)).isEqualTo("Create a Button");

        Document docsChunk = doc("2", "Path: Flow UI > Button\n\nSome plain docs text", Map.of("docPath", "Flow UI > Button"));
        assertThat(SearchResultsFormatter.extractTitle(docsChunk)).isEqualTo("Flow UI > Button");

        Document plain = doc("3", "Just a plain chunk of text\nsecond line", Map.of());
        assertThat(SearchResultsFormatter.extractTitle(plain)).isEqualTo("Just a plain chunk of text");
    }

    @Test
    void extractsSourceUrlWithFallback() {
        assertThat(SearchResultsFormatter.extractSource(
                doc("1", "t", Map.of("url", "https://docs.jmix.io/x", "source", "x.html"))))
                .isEqualTo("https://docs.jmix.io/x");
        assertThat(SearchResultsFormatter.extractSource(doc("2", "t", Map.of("source", "x.html"))))
                .isEqualTo("x.html");
        assertThat(SearchResultsFormatter.extractSource(doc("3", "t", Map.of()))).isEmpty();
    }
}
