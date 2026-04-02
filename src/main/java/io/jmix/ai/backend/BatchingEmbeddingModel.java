package io.jmix.ai.backend;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

public class BatchingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final int maxBatchSize;

    public BatchingEmbeddingModel(EmbeddingModel delegate, int maxBatchSize) {
        this.delegate = delegate;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        if (instructions == null || instructions.size() <= maxBatchSize) {
            return delegate.call(request);
        }

        List<Embedding> embeddings = new ArrayList<>();
        for (int start = 0; start < instructions.size(); start += maxBatchSize) {
            int end = Math.min(start + maxBatchSize, instructions.size());
            EmbeddingRequest batchRequest = new EmbeddingRequest(instructions.subList(start, end), request.getOptions());
            EmbeddingResponse batchResponse = delegate.call(batchRequest);
            for (Embedding embedding : batchResponse.getResults()) {
                embeddings.add(new Embedding(embedding.getOutput(), embeddings.size(), embedding.getMetadata()));
            }
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        if (documents == null || documents.size() <= maxBatchSize) {
            return delegate.embed(documents, options, batchingStrategy);
        }

        List<float[]> embeddings = new ArrayList<>();
        for (int start = 0; start < documents.size(); start += maxBatchSize) {
            int end = Math.min(start + maxBatchSize, documents.size());
            embeddings.addAll(delegate.embed(documents.subList(start, end), options, batchingStrategy));
        }
        return embeddings;
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
