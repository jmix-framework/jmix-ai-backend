package io.jmix.ai.backend.vectorstore.trainings;

import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.Chunker;
import io.jmix.ai.backend.vectorstore.ChunkTextSplitter;
import io.jmix.ai.backend.vectorstore.KnowledgeSourceManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.apache.commons.lang3.stream.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class TrainingsIngester extends AbstractIngester {

    private static final Logger log = LoggerFactory.getLogger(TrainingsIngester.class);

    private final int limit;
    private final List<String> whitelist;
    private final List<String> blacklist;
    private final Path localPath;
    private final String primaryLanguage;
    private final Chunker chunker;
    private final ChunkTextSplitter chunkTextSplitter;
    private final TrainingSourceLoader trainingSourceLoader;

    public TrainingsIngester(
            @Value("${trainings.local-path}") String localPath,
            @Value("${trainings.limit}") int limit,
            @Value("${trainings.whitelist}") List<String> whitelist,
            @Value("${trainings.blacklist}") List<String> blacklist,
            @Value("${trainings.primary-language:RU}") String primaryLanguage,
            @Value("${vectorstore.add-batch-size:128}") int vectorStoreAddBatchSize,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            KnowledgeSourceManager knowledgeSourceManager) {
        super(vectorStore, timeSource, vectorStoreRepository, knowledgeSourceManager, vectorStoreAddBatchSize);
        this.limit = limit;
        this.whitelist = whitelist;
        this.localPath = Path.of(localPath);
        this.blacklist = blacklist;
        this.primaryLanguage = primaryLanguage.toUpperCase();
        chunker = new TrainingsChunker(MAX_CHUNK_SIZE, 300);
        chunkTextSplitter = new ChunkTextSplitter(MAX_CHUNK_SIZE, CHUNK_OVERLAP);
        trainingSourceLoader = new TrainingSourceLoader();
    }

    @Override
    public String getType() {
        return "trainings";
    }

    @Override
    protected void prepareUpdate() {
        super.prepareUpdate();
        Path effectiveLocalPath = effectiveLocalPath();
        if (!Files.isDirectory(effectiveLocalPath)) {
            throw new IllegalStateException("Trainings repository not found: " + effectiveLocalPath);
        }
        List<String> missingRoots = whitelist.stream()
                .filter(root -> Files.notExists(effectiveLocalPath.resolve(root)))
                .toList();
        if (!missingRoots.isEmpty()) {
            throw new IllegalStateException("Trainings repository is incomplete at " + effectiveLocalPath
                    + ". Missing roots: " + String.join(", ", missingRoots));
        }
    }

    @Override
    protected List<String> loadSources() {
        return loadListOfTrainingDocs().stream()
                .map(path -> effectiveLocalPath().relativize(path).toString())
                .toList();
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    private List<Path> loadListOfTrainingDocs() {
        Path effectiveLocalPath = effectiveLocalPath();
        log.info("Loading list of trainings from {}", effectiveLocalPath);
        try (Stream<Path> walk = Files.walk(effectiveLocalPath)) {
            return walk.filter(Files::isRegularFile)
                    .filter(this::isValidTrainingFile)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load list of training docs", e);
        }
    }

    private boolean isValidTrainingFile(Path path) {
        String pathStr = path.toString();
        String fileName = path.getFileName().toString();
        return pathStr.endsWith(".adoc") &&
                (fileName.contains("_" + effectivePrimaryLanguage()) || fileName.contains("-" + effectivePrimaryLanguage())) &&
                Streams.of(path).anyMatch(p -> whitelist.contains(p.toString())) &&
                Streams.of(path).noneMatch(p -> blacklist.contains(p.toString()));
    }

    @Override
    protected Document loadDocument(String source) {
        Path docPath = effectiveLocalPath().resolve(source);
        log.debug("Loading training doc: {}", docPath);

        String textContent;
        try {
            textContent = trainingSourceLoader.load(docPath);
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to load training doc: {}", docPath);
            return null;
        }

        Map<String, Object> metadata = createMetadata(source, textContent);
        metadata.put("language", effectivePrimaryLanguage().toLowerCase());
        metadata.put("docPath", source);
        return createDocument(textContent, metadata);
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Chunker.Chunk> chunks = chunker.extract(document.getText(), "");

            Map<String, Object> metadata = document.getMetadata();
            String url = (String) metadata.get("url");

            for (Chunker.Chunk chunk : chunks) {
                List<Chunker.Chunk> safeChunks = chunkTextSplitter.split(chunk);
                for (Chunker.Chunk safeChunk : safeChunks) {
                    Map<String, Object> metadataCopy = copyMetadata(metadata);
                    metadataCopy.put("size", safeChunk.text().length());
                    if (safeChunk.anchor() != null) {
                        metadataCopy.put("url", url + safeChunk.anchor());
                    }
                    Document chunkDoc = createDocument(safeChunk.text(), metadataCopy);
                    chunkDocs.add(chunkDoc);
                }
            }
        }
        return chunkDocs;
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        return metadata.entrySet()
                .stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Path effectiveLocalPath() {
        String location = getKnowledgeSourceLocation();
        return location != null ? Path.of(location) : localPath;
    }

    private String effectivePrimaryLanguage() {
        String language = getKnowledgeSourceLanguage();
        return language != null ? language.toUpperCase() : primaryLanguage;
    }
}
