package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.vectorstore.CorpusType;
import org.springframework.ai.vectorstore.VectorStore;

public class JavaApiTool extends AbstractRagTool {

    public JavaApiTool(
            VectorStore vectorStore,
            PostRetrievalProcessor postRetrievalProcessor,
            Reranker reranker,
            ParametersReader parametersReader,
            JmixVersion jmixVersion) {
        super("javaapi_retriever", CorpusType.JAVA_API, vectorStore, postRetrievalProcessor, reranker,
                parametersReader, jmixVersion, true);
    }
}
