package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

public class FrameworkTool extends AbstractRagTool {

    public FrameworkTool(VectorStore vectorStore, PostRetrievalProcessor postRetrievalProcessor, Reranker reranker,
                         ParametersReader parametersReader, List<Document> retrievedDocuments, ToolEventListener listener) {
        super("framework_retriever", "jmix-framework-code", vectorStore, postRetrievalProcessor, reranker,
                parametersReader, retrievedDocuments, listener);
    }
}
