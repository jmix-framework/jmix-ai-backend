package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.vectorstore.CorpusType;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

public class JavaApiTool extends AbstractRagTool {

    public JavaApiTool(
            VectorStore vectorStore,
            PostRetrievalProcessor postRetrievalProcessor,
            Reranker reranker,
            ParametersReader parametersReader,
            List<Document> retrievedDocuments,
            ToolEventListener listener,
            JmixVersion jmixVersion) {
        super("javaapi_retriever", CorpusType.JAVA_API, vectorStore, postRetrievalProcessor, reranker,
                parametersReader, retrievedDocuments, listener, jmixVersion, true);
    }
}
