package io.jmix.ai.backend.vectorstore.trainings;

import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.Chunker;
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

//    private final String gitUrl;
    private final int limit;
    private final List<String> whitelist;
    private final List<String> blacklist;
    private final Path localPath;
    private final Chunker chunker;

    public TrainingsIngester(
//            @Value("${trainings.git-url}") String gitUrl,
            @Value("${trainings.local-path}") String localPath,
            @Value("${trainings.limit}") int limit,
            @Value("${trainings.whitelist}") List<String> whitelist,
            @Value("${trainings.blacklist}") List<String> blacklist,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        super(vectorStore, timeSource, vectorStoreRepository);
//        this.gitUrl = gitUrl;
        this.limit = limit;
        this.whitelist = whitelist;
        this.localPath = Path.of(localPath);
        this.blacklist = blacklist;
        chunker = new TrainingsChunker(MAX_CHUNK_SIZE, 300);
    }

    @Override
    public String getType() {
        return "trainings";
    }

//    @Override
//    protected void prepareUpdate() {
//         updateLocalGitRepo();
//    }
//
//    private void updateLocalGitRepo() {
//        Path repoDir = localPath;
//        try {
//            if (!(Files.exists(repoDir)
//                    && Files.isDirectory(repoDir)
//                    && Files.exists(repoDir.resolve(".git")))) {
//                log.info("Cloning repository from {}", gitUrl);
//                executeCommand("git", "clone", gitUrl, localPath.toString());
//            } else {
//                log.info("Pulling updates for repository at {}", localPath);
//                executeCommand("git", "-C", localPath.toString(), "pull");
//            }
//        } catch (IOException | InterruptedException e) {
//            throw new RuntimeException("Failed to update local git repository", e);
//        }
//    }
//
//    private void executeCommand(String... command) throws IOException, InterruptedException {
//        int exitCode = new ProcessBuilder(command)
//                .inheritIO()
//                .start()
//                .waitFor();
//        if (exitCode != 0) {
//            throw new RuntimeException("Command failed with exit code: " + exitCode);
//        }
//    }

    @Override
    protected List<String> loadSources() {
        return loadListOfTrainingDocs().stream()
                .map(path -> localPath.relativize(path).toString())
                .toList();
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    private List<Path> loadListOfTrainingDocs() {
        log.info("Loading list of trainings from {}", localPath);
        try (Stream<Path> walk = Files.walk(localPath)) {
            return walk.filter(Files::isRegularFile)
                    .filter(this::isValidTrainingFile)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load list of training docs", e);
        }
    }

    private boolean isValidTrainingFile(Path path) {
        String pathStr = path.toString();
        return pathStr.endsWith(".adoc") &&
                (pathStr.contains("_EN") || pathStr.contains("-EN")) &&
                Streams.of(path).anyMatch(p -> whitelist.contains(p.toString())) &&
                Streams.of(path).noneMatch(p -> blacklist.contains(p.toString()));
    }

    @Override
    protected Document loadDocument(String source) {
        Path docPath = localPath.resolve(source);
        log.debug("Loading training doc: {}", docPath);

        String textContent;
        try {
            textContent = Files.readString(docPath);
        } catch (IOException e) {
            log.warn("Failed to load training doc: {}", docPath);
            return null;
        }

        Map<String, Object> metadata = createMetadata(source, textContent);
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
                Map<String, Object> metadataCopy = copyMetadata(metadata);
                metadataCopy.put("size", chunk.text().length());
                if (chunk.anchor() != null) {
                    metadataCopy.put("url", url + chunk.anchor());
                }
                Document chunkDoc = createDocument(chunk.text(), metadataCopy);
                chunkDocs.add(chunkDoc);
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
}
