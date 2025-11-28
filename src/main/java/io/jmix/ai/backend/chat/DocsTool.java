package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.function.Consumer;

public class DocsTool extends AbstractRagTool {

    public DocsTool(VectorStore vectorStore, PostRetrievalProcessor postRetrievalProcessor, Reranker reranker, ParametersReader parametersReader,
                       List<Document> retrievedDocuments, Consumer<String> logger) {
        super("documentation_retriever", "docs", vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, logger);
    }
}
