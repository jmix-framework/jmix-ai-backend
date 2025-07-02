package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.Parameters;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.List;

public class CustomDocumentPostProcessor implements DocumentPostProcessor {

    public static final int DOCS_COUNT = 15;
    public static final int UISAMPLES_COUNT = 3;
    public static final int TRAININGS_COUNT = 2;

    private final List<Document> retrievedDocuments;

    public CustomDocumentPostProcessor(Parameters parameters, List<Document> retrievedDocuments) {
        this.retrievedDocuments = retrievedDocuments;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        List<Document> processedDocs = new ArrayList<>();

        int docsCount = 0;
        int uisamplesCount = 0;
        int trainingsCount = 0;
        for (Document document : documents) {
            String type = (String) document.getMetadata().get("type");
            switch (type) {
                case "docs" -> {
                    docsCount++;
                    if (docsCount <= DOCS_COUNT) {
                        processedDocs.add(document);
                    }
                }
                case "uisamples" -> {
                    uisamplesCount++;
                    if (uisamplesCount <= UISAMPLES_COUNT) {
                        processedDocs.add(document);
                    }
                }
                case "trainings" -> {
                    trainingsCount++;
                    if (trainingsCount <= TRAININGS_COUNT) {
                        processedDocs.add(document);
                    }
                }
            }
        }
        retrievedDocuments.addAll(processedDocs);
        return processedDocs;
    }
}
