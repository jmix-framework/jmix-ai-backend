package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.function.Consumer;

public class UiSamplesTool extends AbstractRagTool {

    public UiSamplesTool(VectorStore vectorStore, ParametersReader parametersReader,
                         List<Document> retrievedDocuments, Consumer<String> logger) {
        super("uisamples_retriever", "uisamples", vectorStore, parametersReader, retrievedDocuments, logger);
    }
}
