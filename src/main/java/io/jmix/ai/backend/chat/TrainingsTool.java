package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.function.Consumer;

public class TrainingsTool extends AbstractRagTool {

    public TrainingsTool(VectorStore vectorStore, ParametersReader parametersReader, Consumer<String> logger) {
        super("trainings_retriever", "trainings", vectorStore, parametersReader, logger);
    }

    @Override
    protected String getLogMessage(Document document) {
        return "(" + String.format("%.3f", document.getScore()) + ") " + document.getMetadata().get("source");
    }
}
