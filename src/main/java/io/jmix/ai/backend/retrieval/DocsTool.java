package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.vectorstore.VectorStore;

public class DocsTool extends AbstractRagTool {

    public DocsTool(VectorStore vectorStore,
                    PostRetrievalProcessor postRetrievalProcessor,
                    Reranker reranker,
                    ParametersReader parametersReader,
                    JmixVersion jmixVersion) {
        super("documentation_retriever", "docs", vectorStore, postRetrievalProcessor, reranker,
                parametersReader, jmixVersion, true);
    }
}
