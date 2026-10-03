package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.vectorstore.VectorStore;

public class UiSamplesTool extends AbstractRagTool {

    public UiSamplesTool(VectorStore vectorStore,
                         PostRetrievalProcessor postRetrievalProcessor,
                         Reranker reranker,
                         ParametersReader parametersReader,
                         JmixVersion jmixVersion) {
        super("uisamples_retriever", "uisamples", vectorStore, postRetrievalProcessor, reranker,
                parametersReader, jmixVersion, true);
    }
}
