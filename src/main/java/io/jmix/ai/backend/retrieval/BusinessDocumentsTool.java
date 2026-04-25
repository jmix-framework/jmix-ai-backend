package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.function.Consumer;

public class BusinessDocumentsTool extends AbstractRagTool {

    private final BusinessMetricsService businessMetricsService;

    public BusinessDocumentsTool(VectorStore vectorStore,
                                 PostRetrievalProcessor postRetrievalProcessor,
                                 Reranker reranker,
                                 BusinessMetricsService businessMetricsService,
                                 ParametersReader parametersReader,
                                 List<Document> retrievedDocuments,
                                 Consumer<String> logger) {
        super("business_documents_retriever", "business-documents", vectorStore, postRetrievalProcessor, reranker,
                parametersReader, retrievedDocuments, logger);
        this.businessMetricsService = businessMetricsService;
    }

    @Override
    public String execute(String queryText) {
        BusinessMetricsService.BusinessMetricsResult metricsResult = businessMetricsService.analyze(queryText);
        logger.accept(">>> Using business_documents_retriever as deterministic metrics tool: " + queryText);
        if (metricsResult == null) {
            logger.accept(">>> business_documents_retriever is not applicable for this query");
            return "";
        }
        retrievedDocuments.addAll(metricsResult.supportingDocuments());
        logger.accept(">>> business_documents_retriever produced deterministic answer");
        return metricsResult.answer();
    }
}
