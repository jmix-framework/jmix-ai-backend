package io.jmix.ai.backend.vectorstore.business;

import io.jmix.ai.backend.entity.KnowledgeSource;

import java.util.Set;

public final class BusinessDocumentsSupport {

    public static final String KNOWLEDGE_BASE_CODE = "business-documents-demo";
    public static final String KNOWLEDGE_SOURCE_CODE = "business-documents-local";
    public static final String UPLOADS_DIR = "uploads";
    public static final Set<String> INCLUDED_EXTENSIONS = Set.of(
            ".txt",
            ".md",
            ".adoc",
            ".csv",
            ".json",
            ".xml",
            ".html",
            ".htm",
            ".log"
    );

    private BusinessDocumentsSupport() {
    }

    public static boolean isBusinessDocumentsSource(KnowledgeSource source) {
        return source != null
                && source.getKnowledgeBase() != null
                && KNOWLEDGE_BASE_CODE.equals(source.getKnowledgeBase().getCode())
                && KNOWLEDGE_SOURCE_CODE.equals(source.getCode());
    }

    public static String acceptedFileTypesCsv() {
        return String.join(", ", INCLUDED_EXTENSIONS);
    }
}
