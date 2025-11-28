package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.function.Consumer;

public class TrainingsTool extends AbstractRagTool {

    public TrainingsTool(VectorStore vectorStore, PostRetrievalProcessor postRetrievalProcessor, Reranker reranker, ParametersReader parametersReader,
                         List<Document> retrievedDocuments, Consumer<String> logger) {
        super("trainings_retriever", "trainings", vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, logger);
    }
}
