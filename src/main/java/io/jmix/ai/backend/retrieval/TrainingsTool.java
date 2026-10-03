package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.vectorstore.VectorStore;

public class TrainingsTool extends AbstractRagTool {

    public TrainingsTool(VectorStore vectorStore,
                         PostRetrievalProcessor postRetrievalProcessor,
                         Reranker reranker,
                         ParametersReader parametersReader,
                         JmixVersion jmixVersion) {
        super("trainings_retriever", "trainings", vectorStore, postRetrievalProcessor, reranker,
                parametersReader, jmixVersion, false);
    }
}
