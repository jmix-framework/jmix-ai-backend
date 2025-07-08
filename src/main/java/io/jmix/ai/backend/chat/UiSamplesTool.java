package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.function.Consumer;

public class UiSamplesTool extends AbstractRagTool {

    public UiSamplesTool(VectorStore vectorStore, ParametersReader parametersReader, Consumer<String> logger) {
        super("uisamples_retriever", "uisamples", vectorStore, parametersReader, logger);
    }
}
