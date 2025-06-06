package io.jmix.ai.backend.vectorstore;

import io.jmix.core.CoreProperties;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class TrainingsRetriever extends AbstractRetriever {

    private static final Logger log = LoggerFactory.getLogger(TrainingsRetriever.class);

    private final String gitUrl;
    private final int limit;
    private final List<String> whitelist;
    private final Path gitLocalPath;

    public TrainingsRetriever(
            @Value("${trainings.git-url}") String gitUrl,
            @Value("${trainings.limit}") int limit,
            @Value("${trainings.whitelist}") List<String> whitelist,
            CoreProperties coreProperties,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        super(vectorStore, timeSource, vectorStoreRepository);
        this.gitUrl = gitUrl;
        this.limit = limit;
        this.whitelist = whitelist;
        gitLocalPath = Path.of(coreProperties.getWorkDir(), "trainings");
    }

    @Override
    public String getType() {
        return "trainings";
    }

    @Override
    protected void prepareUpdate() {
         updateLocalGitRepo();
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
    protected List<String> loadSources() {
        return loadListOfTrainingDocs().stream()
                .map(path -> gitLocalPath.relativize(path).toString())
                .toList();
    }

    @Override
    protected int getSourceLimit() {
        return limit;
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

    @Override
    protected Document loadDocument(String source) {
        Path docPath = gitLocalPath.resolve(source);
        log.debug("Loading training doc: {}", docPath);

        String textContent;
        try {
            textContent = Files.readString(docPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load training doc: " + docPath, e);
        }

        Map<String, Object> metadata = createMetadata(source, textContent);
        return createDocument(textContent, metadata);
    }
}
