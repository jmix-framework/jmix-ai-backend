package io.jmix.ai.backend.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
