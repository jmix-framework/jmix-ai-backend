package io.jmix.ai.backend;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchingEmbeddingModelTest {

    @Test
    void splitsCallIntoFixedSizeBatches() {
        RecordingEmbeddingModel delegate = new RecordingEmbeddingModel();
        BatchingEmbeddingModel model = new BatchingEmbeddingModel(delegate, 2);

        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("a", "b", "c", "d", "e"), null));

        assertThat(delegate.batchSizes).containsExactly(2, 2, 1);
        assertThat(response.getResults()).hasSize(5);
        assertThat(response.getResults()).extracting(Embedding::getIndex).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void splitsDocumentEmbeddingIntoFixedSizeBatches() {
        RecordingEmbeddingModel delegate = new RecordingEmbeddingModel();
        BatchingEmbeddingModel model = new BatchingEmbeddingModel(delegate, 2);

        List<float[]> embeddings = model.embed(
                List.of(new Document("1"), new Document("2"), new Document("3"), new Document("4"), new Document("5")),
                null,
                documents -> List.of(documents)
        );

        assertThat(delegate.documentBatchSizes).containsExactly(2, 2, 1);
        assertThat(embeddings).hasSize(5);
    }

    private static class RecordingEmbeddingModel implements EmbeddingModel {

        private final List<Integer> batchSizes = new ArrayList<>();
        private final List<Integer> documentBatchSizes = new ArrayList<>();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            batchSizes.add(request.getInstructions().size());
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(new float[]{i}, i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return new float[]{1f};
        }

        @Override
        public List<float[]> embed(List<Document> documents, org.springframework.ai.embedding.EmbeddingOptions options,
                                   org.springframework.ai.embedding.BatchingStrategy batchingStrategy) {
            documentBatchSizes.add(documents.size());
            List<float[]> embeddings = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                embeddings.add(new float[]{i});
            }
            return embeddings;
        }
    }
}
