package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.function.Consumer;

public class TrainingsTool extends AbstractRagTool {

    public TrainingsTool(VectorStore vectorStore, Reranker reranker, ParametersReader parametersReader,
                         List<Document> retrievedDocuments, Consumer<String> logger) {
        super("trainings_retriever", "trainings", vectorStore, reranker, parametersReader, retrievedDocuments, logger);
    }

    protected String getLogMessage(Document document) {
        return "(" + String.format("%.3f", document.getScore()) + ") " + document.getMetadata().get("source");
    }
}
