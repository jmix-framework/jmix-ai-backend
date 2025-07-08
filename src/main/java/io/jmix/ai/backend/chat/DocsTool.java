package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.function.Consumer;

public class DocsTool extends AbstractRagTool {

    protected DocsTool(VectorStore vectorStore, ParametersReader parametersReader, Consumer<String> logger) {
        super("documentation_retriever", "docs", vectorStore, parametersReader, logger);
    }
}
