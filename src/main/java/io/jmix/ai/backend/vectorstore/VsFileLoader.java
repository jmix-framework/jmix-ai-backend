package io.jmix.ai.backend.vectorstore;

import io.jmix.core.UuidProvider;
import org.jsoup.Jsoup;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

//@Component
public class VsFileLoader {

    private final VectorStore vectorStore;

    public VsFileLoader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void loadDocument(String filePath) {
        Path path = Path.of(filePath);
        String htmlContent = getContent(path);

        // Extract text content from HTML using Jsoup, selecting only article.doc content
        String textContent = Jsoup.parse(htmlContent).select("article.doc").text();

        // Create a Spring AI Document with the text content
        Document document = new Document(
                UuidProvider.createUuidV7().toString(),
                textContent,
                createMetadata(path.toFile())
        );

        vectorStore.add(List.of(document));
    }

    private static String getContent(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private java.util.Map<String, Object> createMetadata(File file) {
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("filename", file.getName());
        metadata.put("path", file.getAbsolutePath());
        metadata.put("lastModified", file.lastModified());
        metadata.put("size", file.length());
        return metadata;
    }
}
