package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractRagToolTest {

    @Test
    void applyRerankThresholdReturnsMatchingDocumentsWhenThresholdIsMet() {
        List<Reranker.Result> results = List.of(
                new Reranker.Result(new Document("doc-1"), 0.9),
                new Reranker.Result(new Document("doc-2"), 0.3)
        );

        List<Reranker.Result> filtered = AbstractRagTool.applyRerankThreshold(results, 0.5, true, 1);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.getFirst().score()).isEqualTo(0.9);
    }

    @Test
    void applyRerankThresholdFallsBackToTopResultWhenEnabledAndNothingPassesThreshold() {
        List<Reranker.Result> results = List.of(
                new Reranker.Result(new Document("doc-1"), 0.44),
                new Reranker.Result(new Document("doc-2"), 0.08)
        );

        List<Reranker.Result> filtered = AbstractRagTool.applyRerankThreshold(results, 0.5, true, 1);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.getFirst().score()).isEqualTo(0.44);
    }

    @Test
    void applyRerankThresholdDoesNotFallbackWhenDisabled() {
        List<Reranker.Result> results = List.of(
                new Reranker.Result(new Document("doc-1"), 0.44),
                new Reranker.Result(new Document("doc-2"), 0.08)
        );

        List<Reranker.Result> filtered = AbstractRagTool.applyRerankThreshold(results, 0.5, false, 1);

        assertThat(filtered).isEmpty();
    }

    @Test
    void applyRerankThresholdCanFallbackToTopTwoResultsForFrameworkQueries() {
        List<Reranker.Result> results = List.of(
                new Reranker.Result(new Document("doc-1"), 0.00029),
                new Reranker.Result(new Document("doc-2"), 0.00028),
                new Reranker.Result(new Document("doc-3"), 0.00019)
        );

        List<Reranker.Result> filtered = AbstractRagTool.applyRerankThreshold(results, 0.5, true, 2);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).document().getText()).isEqualTo("doc-1");
        assertThat(filtered.get(1).document().getText()).isEqualTo("doc-2");
    }

    @Test
    void executeSearchDeduplicatesDocumentsBeforeRerank() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document first = new Document("1", "first", Map.of(
                "url", "https://docs.jmix.ru/1.x/jmix/1.7/security/resource-roles.html#example",
                "source", "security/resource-roles.html"
        ));
        Document second = new Document("2", "second", Map.of(
                "url", "https://docs.jmix.ru/1.x/jmix/1.7/security/resource-roles.html#create",
                "source", "security/resource-roles.html"
        ));
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of(first, second));

        AtomicReference<List<Document>> rerankInput = new AtomicReference<>(List.of());
        Reranker reranker = new Reranker("http://localhost:8000/rerank") {
            @Override
            public List<Result> rerank(String query, List<Document> documents, int topN) {
                rerankInput.set(List.copyOf(documents));
                return documents.stream()
                        .map(document -> new Result(document, 1.0))
                        .limit(topN)
                        .toList();
            }
        };

        ParametersReader parametersReader = new ParametersReader(Map.of(
                "tools", Map.of(
                        "test_tool", Map.of(
                                "description", "Test tool",
                                "similarityThreshold", 0.0,
                                "topK", 10,
                                "topReranked", 3,
                                "minScore", 0.0,
                                "minRerankedScore", 0.0,
                                "noResultsMessage", "No results"
                        )
                ),
                "postRetrievalProcessor", Map.of("rules", List.of())
        ));

        PostRetrievalProcessor processor = new PostRetrievalProcessor(parametersReader, null);
        TestRagTool tool = new TestRagTool(vectorStore, processor, reranker, parametersReader);

        tool.execute("query");

        assertThat(rerankInput.get()).containsExactly(first);
    }

    private static class TestRagTool extends AbstractRagTool {

        protected TestRagTool(VectorStore vectorStore, PostRetrievalProcessor postRetrievalProcessor,
                              Reranker reranker, ParametersReader parametersReader) {
            super("test_tool", "test", vectorStore, postRetrievalProcessor, reranker,
                    parametersReader, new java.util.ArrayList<>(), message -> {
                    });
        }
    }
}
