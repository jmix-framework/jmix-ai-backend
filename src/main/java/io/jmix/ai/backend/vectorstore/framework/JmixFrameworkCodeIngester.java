package io.jmix.ai.backend.vectorstore.framework;

import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.ChunkTextSplitter;
import io.jmix.ai.backend.vectorstore.Chunker;
import io.jmix.ai.backend.vectorstore.KnowledgeSourceManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class JmixFrameworkCodeIngester extends AbstractIngester {

    private static final List<String> ROOT_FILES = List.of(
            "gradle.properties",
            "settings.gradle",
            "build.gradle",
            "README.md",
            "LICENSE.txt"
    );

    private static final Set<String> INCLUDED_EXTENSIONS = Set.of(
            ".java",
            ".groovy",
            ".kt",
            ".xml",
            ".properties",
            ".yml",
            ".yaml",
            ".gradle",
            ".md",
            ".txt"
    );

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git",
            ".idea",
            ".gradle",
            "build",
            "out",
            "node_modules",
            "target"
    );

    private final Path localPath;
    private final int limit;
    private final List<String> moduleWhitelist;
    private final String githubBaseUrl;
    private final ChunkTextSplitter chunkTextSplitter;

    public JmixFrameworkCodeIngester(
            @Value("${jmix-framework.local-path}") String localPath,
            @Value("${jmix-framework.limit}") int limit,
            @Value("${jmix-framework.module-whitelist}") List<String> moduleWhitelist,
            @Value("${jmix-framework.github-base-url:https://github.com/jmix-framework/jmix/tree/v1.7.2}") String githubBaseUrl,
            @Value("${vectorstore.add-batch-size:128}") int vectorStoreAddBatchSize,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            KnowledgeSourceManager knowledgeSourceManager) {
        super(vectorStore, timeSource, vectorStoreRepository, knowledgeSourceManager, vectorStoreAddBatchSize);
        this.localPath = Path.of(localPath);
        this.limit = limit;
        this.moduleWhitelist = moduleWhitelist;
        this.githubBaseUrl = githubBaseUrl;
        this.chunkTextSplitter = new ChunkTextSplitter(MAX_CHUNK_SIZE, CHUNK_OVERLAP);
    }

    @Override
    public String getType() {
        return "jmix-framework-code";
    }

    @Override
    protected void prepareUpdate() {
        super.prepareUpdate();
        Path effectiveLocalPath = effectiveLocalPath();
        if (!Files.isDirectory(effectiveLocalPath)) {
            throw new IllegalStateException("Jmix framework sources not found: " + effectiveLocalPath);
        }

        List<String> missingModules = moduleWhitelist.stream()
                .filter(module -> Files.notExists(effectiveLocalPath.resolve(module)))
                .toList();
        if (!missingModules.isEmpty()) {
            throw new IllegalStateException("Jmix framework source snapshot is incomplete at " + effectiveLocalPath
                    + ". Missing modules: " + String.join(", ", missingModules));
        }
    }

    @Override
    protected List<String> loadSources() {
        Path effectiveLocalPath = effectiveLocalPath();
        Set<String> sources = new LinkedHashSet<>();

        for (String rootFile : ROOT_FILES) {
            if (Files.exists(effectiveLocalPath.resolve(rootFile))) {
                sources.add(rootFile);
            }
        }

        for (String module : moduleWhitelist) {
            Path modulePath = effectiveLocalPath.resolve(module);
            try (Stream<Path> walk = Files.walk(modulePath)) {
                walk.filter(Files::isRegularFile)
                        .filter(this::isIncludedFile)
                        .filter(path -> !isExcluded(path))
                        .map(effectiveLocalPath::relativize)
                        .map(Path::toString)
                        .sorted()
                        .forEach(sources::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan Jmix framework sources in " + modulePath, e);
            }
        }

        return List.copyOf(sources);
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    @Override
    protected Document loadDocument(String source) {
        Path file = effectiveLocalPath().resolve(source);
        try {
            String content = Files.readString(file);
            String textContent = buildDocumentText(source, content);
            Map<String, Object> metadata = createMetadata(source, textContent);
            metadata.put("docPath", source);
            metadata.put("module", topLevelSegment(source));
            metadata.put("fileType", extensionOf(source));
            metadata.put("url", toGithubUrl(source));
            return createDocument(textContent, metadata);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Jmix framework source file: " + file, e);
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

    private String buildDocumentText(String source, String content) {
        return "Module: %s\nPath: %s\nFile type: %s\n\n%s".formatted(
                topLevelSegment(source),
                source,
                extensionOf(source),
                content
        );
    }

    private boolean isIncludedFile(Path path) {
        String fileName = path.getFileName().toString();
        if ("build.gradle".equals(fileName)) {
            return true;
        }
        String relativePath = path.toString().replace('\\', '/');
        if (!relativePath.contains("/src/main/")) {
            return false;
        }
        return INCLUDED_EXTENSIONS.contains(extensionOf(fileName));
    }

    private boolean isExcluded(Path path) {
        for (Path part : path) {
            if (EXCLUDED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String topLevelSegment(String source) {
        int slashIndex = source.indexOf('/');
        return slashIndex >= 0 ? source.substring(0, slashIndex) : "root";
    }

    private String extensionOf(String path) {
        int dotIndex = path.lastIndexOf('.');
        return dotIndex >= 0 ? path.substring(dotIndex) : "";
    }

    private String toGithubUrl(String source) {
        String normalizedBase = githubBaseUrl.endsWith("/") ? githubBaseUrl.substring(0, githubBaseUrl.length() - 1) : githubBaseUrl;
        return normalizedBase + "/" + source.replace('\\', '/');
    }

    private Path effectiveLocalPath() {
        String location = getKnowledgeSourceLocation();
        return location != null ? Path.of(location) : localPath;
    }
}
