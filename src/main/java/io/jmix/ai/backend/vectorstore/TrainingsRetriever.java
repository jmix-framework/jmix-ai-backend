package io.jmix.ai.backend.vectorstore;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.CoreProperties;
import io.jmix.core.TimeSource;
import io.jmix.core.UuidProvider;
import org.apache.commons.lang3.stream.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Component
public class TrainingsRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(TrainingsRetriever.class);

    private final String gitUrl;
    private final int limit;
    private final List<String> whitelist;
    private final Path gitLocalPath;
    private final VectorStore vectorStore;
    private final TimeSource timeSource;
    private final VectorStoreRepository vectorStoreRepository;
    private final TextSplitter textSplitter;

    // A chunk size of 4000 tokens is roughly half the OpenAI limit (8192), providing a safe buffer for
    // metadata, formatting, or encoding variations. For a 100KB document (~16,667-20,000 tokens),
    // this would yield ~4-5 chunks, which is manageable for retrieval and maintains sufficient context.
    // For a 20KB document (~3,333-4,000 tokens), it results in 1-2 chunks, which is efficient.
    private static final int DEFAULT_CHUNK_SIZE = 4000;

    // Assuming 5-6 characters per token, 1000 characters is ~167-200 tokens, ensuring chunks are substantial
    // enough to capture meaningful content (e.g., a few sentences or a paragraph). This is higher than
    // the default (350) to avoid overly small chunks for larger documents, which may contain
    // structured content like headings or lists.
    private static final int MIN_CHUNK_SIZE_CHARS = 1000;

    // This ensures that only chunks with sufficient content (e.g., a sentence or two) are embedded,
    // filtering out trivial fragments. A value of 50 tokens (~250-300 characters) is reasonable for documents,
    // which may have short sections or list items that should still be included.
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 50;

    // A 100KB document (~16,667-20,000 tokens) split into 4000-token chunks could produce ~4-5 chunks,
    // but larger or more complex documents might generate more. Setting maxNumChunks to 100 provides ample
    // headroom for larger documents while preventing excessive splitting. This is much lower than the default (10,000),
    // which is unnecessarily high for the use case.
    private static final int MAX_NUM_CHUNKS = 100;

    private static final boolean KEEP_SEPARATOR = true;

    public TrainingsRetriever(
            @Value("${trainings.git-url}") String gitUrl,
            @Value("${trainings.limit}") int limit,
            @Value("${trainings.whitelist}") List<String> whitelist,
            CoreProperties coreProperties,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        this.gitUrl = gitUrl;
        this.limit = limit;
        this.whitelist = whitelist;
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
        this.vectorStoreRepository = vectorStoreRepository;
        gitLocalPath = Path.of(coreProperties.getWorkDir(), "trainings");
        this.textSplitter = new TokenTextSplitter(
                DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, KEEP_SEPARATOR);
    }

    @Override
    public String getType() {
        return "trainings";
    }

    @Override
    public String updateAll() {
        long start = timeSource.currentTimeMillis();

        updateLocalGitRepo();

        List<Path> docPaths = loadListOfTrainingDocs();
        log.info("Found {} training docs, loading {}", docPaths.size(), limit > 0 ? "first " + limit : "all");

        List<Document> documents = docPaths.stream()
                .limit(limit > 0 ? limit : docPaths.size())
                .map(this::loadTrainingDoc)
                .filter(this::checkContent)
                .toList();

        // Split document into chunks
        List<Document> chunks = textSplitter.apply(documents);
        log.info("Split {} training docs into {} chunks", documents.size(), chunks.size());

        // Add chunks in batches
        log.info("Adding {} documents to vector store", chunks.size());
        vectorStore.add(chunks);

        log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);

        return "loaded: %d, added: %d".formatted(docPaths.size(), documents.size());
    }

    private void updateLocalGitRepo() {
        Path repoDir = gitLocalPath;
        try {
            if (!(Files.exists(repoDir)
                    && Files.isDirectory(repoDir)
                    && Files.exists(repoDir.resolve(".git")))) {
                log.info("Cloning repository from {}", gitUrl);
                executeCommand("git", "clone", gitUrl, gitLocalPath.toString());
            } else {
                log.info("Pulling updates for repository at {}", gitLocalPath);
                executeCommand("git", "-C", gitLocalPath.toString(), "pull");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to update local git repository", e);
        }
    }

    private void executeCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code: " + exitCode);
        }
    }

    @Override
    public String update(VectorStoreEntity entity) {
        updateLocalGitRepo();

        String url = (String) entity.getMetadataMap().get("url");

        List<VectorStoreEntity> entitiesByUrl = vectorStoreRepository.loadList("type == '%s' && url == '%s'".formatted(getType(), url));
        log.info("Loading training doc: {}", url);
        Document document = loadTrainingDoc(gitLocalPath.resolve(url));
        if (!isSameText(document, entity)) {
            vectorStoreRepository.delete(entitiesByUrl);
            List<Document> chunks = textSplitter.apply(List.of(document));
            log.info("Split 1 training doc into {} chunks", chunks.size());
            vectorStore.add(chunks);
            return "updated " + chunks.size() + " documents";
        } else {
            return "no changes";
        }
    }

    private boolean isSameText(Document document, VectorStoreEntity entity) {
        return Objects.equals(entity.getMetadataMap().get("sourceHash"), document.getMetadata().get("sourceHash"));
    }

    private boolean checkContent(Document document) {
        String url = (String) document.getMetadata().get("url");

        List<VectorStoreEntity> entities = vectorStoreRepository.loadList(
                "type == '%s' && url == '%s'".formatted(getType(), url)
        );
        if (entities.isEmpty()) {
            return true;
        }
        boolean contentChanged = false;
        for (VectorStoreEntity entity : entities) {
            if (!isSameText(document, entity)) {
                // Delete existing document since content has changed
                vectorStoreRepository.delete(entity.getId());
                contentChanged = true;
            }
        }
        return contentChanged;
    }

    private List<Path> loadListOfTrainingDocs() {
        try (Stream<Path> walk = Files.walk(gitLocalPath)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> 
                            path.toString().endsWith("_EN.adoc") &&
                                    Streams.of(path).anyMatch(p -> whitelist.contains(p.toString()))
                    )
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load list of training docs", e);
        }
    }

    private Document loadTrainingDoc(Path docPath) {
        log.debug("Loading training doc: {}", docPath);

        String textContent;
        try {
            textContent = Files.readString(docPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load training doc: " + docPath, e);
        }

        return new Document(
                UuidProvider.createUuidV7().toString(),
                textContent,
                createMetadata(gitLocalPath.relativize(docPath).toString(), textContent)
        );
    }

    private Map<String, Object> createMetadata(String url, String textContent) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", getType());
        metadata.put("url", url);
        metadata.put("sourceHash", computeHash(textContent));
        metadata.put("size", textContent.length());
        metadata.put("updated", timeSource.now().toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return metadata;
    }

    private String computeHash(String content) {
        HashCode hash32 = Hashing.murmur3_32_fixed().hashString(content, StandardCharsets.UTF_8);
        return hash32.toString();
    }
}
