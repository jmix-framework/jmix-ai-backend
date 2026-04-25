package io.jmix.ai.backend.vectorstore.business;

import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.ChunkTextSplitter;
import io.jmix.ai.backend.vectorstore.Chunker;
import io.jmix.ai.backend.vectorstore.KnowledgeSourceManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class BusinessDocumentsIngester extends AbstractIngester {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git",
            ".idea",
            ".gradle",
            "build",
            "out",
            "target",
            "node_modules"
    );

    private final Path localPath;
    private final int limit;
    private final ChunkTextSplitter chunkTextSplitter;

    public BusinessDocumentsIngester(@Value("${business-documents.local-path}") String localPath,
                                     @Value("${business-documents.limit:0}") int limit,
                                     @Value("${vectorstore.add-batch-size:128}") int vectorStoreAddBatchSize,
                                     VectorStore vectorStore,
                                     TimeSource timeSource,
                                     VectorStoreRepository vectorStoreRepository,
                                     KnowledgeSourceManager knowledgeSourceManager) {
        super(vectorStore, timeSource, vectorStoreRepository, knowledgeSourceManager, vectorStoreAddBatchSize);
        this.localPath = Path.of(localPath);
        this.limit = limit;
        this.chunkTextSplitter = new ChunkTextSplitter(MAX_CHUNK_SIZE, CHUNK_OVERLAP);
    }

    @Override
    public String getType() {
        return "business-documents";
    }

    @Override
    protected void prepareUpdate() {
        super.prepareUpdate();
        Path effectiveLocalPath = effectiveLocalPath();
        if (!Files.isDirectory(effectiveLocalPath)) {
            throw new IllegalStateException("Business documents directory not found: " + effectiveLocalPath);
        }
    }

    @Override
    protected List<String> loadSources() {
        Path effectiveLocalPath = effectiveLocalPath();
        try (Stream<Path> walk = Files.walk(effectiveLocalPath)) {
            return walk.filter(Files::isRegularFile)
                    .filter(this::isIncludedFile)
                    .filter(path -> !isExcluded(path))
                    .map(effectiveLocalPath::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan business documents in " + effectiveLocalPath, e);
        }
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    @Override
    @Nullable
    protected Document loadDocument(String source) {
        Path file = effectiveLocalPath().resolve(source);
        try {
            String content = Files.readString(file);
            String textContent = buildDocumentText(source, content);
            Map<String, Object> metadata = createMetadata(source, textContent);
            metadata.put("documentPath", source);
            metadata.put("documentName", file.getFileName().toString());
            metadata.put("documentKind", detectDocumentKind(source));
            metadata.put("fileType", extensionOf(source));
            return createDocument(textContent, metadata);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load business document: " + file, e);
        }
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Chunker.Chunk> chunks = chunkTextSplitter.split(new Chunker.Chunk(document.getText(), null));
            for (Chunker.Chunk chunk : chunks) {
                Map<String, Object> metadataCopy = new HashMap<>(document.getMetadata());
                metadataCopy.put("size", chunk.text().length());
                chunkDocs.add(createDocument(chunk.text(), metadataCopy));
            }
        }
        return chunkDocs;
    }

    private boolean isIncludedFile(Path path) {
        return BusinessDocumentsSupport.INCLUDED_EXTENSIONS.contains(extensionOf(path.getFileName().toString()));
    }

    private boolean isExcluded(Path path) {
        for (Path part : path) {
            if (EXCLUDED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String buildDocumentText(String source, String content) {
        return "Business document path: %s%nFile type: %s%n%n%s".formatted(
                source,
                extensionOf(source),
                content
        );
    }

    private String extensionOf(String path) {
        int dotIndex = path.lastIndexOf('.');
        return dotIndex >= 0 ? path.substring(dotIndex) : "";
    }

    private String detectDocumentKind(String source) {
        String normalized = source.replace('\\', '/');
        if (normalized.startsWith("summaries/")) {
            return "summary";
        }
        if (normalized.startsWith("orders/")) {
            return "order";
        }
        return "document";
    }

    private Path effectiveLocalPath() {
        String location = getKnowledgeSourceLocation();
        return location != null ? Path.of(location) : localPath;
    }
}
