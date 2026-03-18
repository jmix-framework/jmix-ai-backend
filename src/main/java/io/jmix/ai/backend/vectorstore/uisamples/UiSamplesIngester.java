package io.jmix.ai.backend.vectorstore.uisamples;

import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.ChunkTextSplitter;
import io.jmix.ai.backend.vectorstore.Chunker;
import io.jmix.ai.backend.vectorstore.KnowledgeSourceManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class UiSamplesIngester extends AbstractIngester {

    private static final Logger log = LoggerFactory.getLogger(UiSamplesIngester.class);

    private static final String MENU_CONFIG = "src/main/resources/io/jmix/sampler/menu/sampler-menu.xml";
    private static final String MESSAGES_EN = "src/main/resources/io/jmix/sampler/messages_en.properties";
    private static final String SCREEN_RESOURCES = "src/main/resources/io/jmix/sampler/screen";
    private static final String SCREEN_JAVA = "src/main/java/io/jmix/sampler/screen";

    private final Path localPath;
    private final String browserBaseUrl;
    private final String githubBaseUrl;
    private final int limit;
    private final ChunkTextSplitter chunkTextSplitter;

    private volatile List<UiSample> cachedSamples;

    public UiSamplesIngester(
            @Value("${uisamples.local-path}") String localPath,
            @Value("${uisamples.browser-base-url:https://demo.jmix.ru/sampler/#main/sample?id=}") String browserBaseUrl,
            @Value("${uisamples.github-base-url:https://github.com/jmix-framework/jmix-ui-samples/tree/master}") String githubBaseUrl,
            @Value("${uisamples.limit}") int limit,
            @Value("${vectorstore.add-batch-size:128}") int vectorStoreAddBatchSize,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository,
            KnowledgeSourceManager knowledgeSourceManager) {
        super(vectorStore, timeSource, vectorStoreRepository, knowledgeSourceManager, vectorStoreAddBatchSize);
        this.localPath = Path.of(localPath);
        this.browserBaseUrl = browserBaseUrl;
        this.githubBaseUrl = githubBaseUrl;
        this.limit = limit;
        this.chunkTextSplitter = new ChunkTextSplitter(MAX_CHUNK_SIZE, CHUNK_OVERLAP);
    }

    @Override
    public String getType() {
        return "uisamples";
    }

    @Override
    protected void prepareUpdate() {
        super.prepareUpdate();
        Path effectiveLocalPath = effectiveLocalPath();
        if (!Files.isDirectory(effectiveLocalPath)) {
            throw new IllegalStateException("UI samples repository not found: " + effectiveLocalPath
                    + ". Clone https://github.com/jmix-framework/jmix-ui-samples.git into this path.");
        }
        validateRepositoryLayout();
        cachedSamples = null;
    }

    @Override
    protected List<String> loadSources() {
        return loadSamples().stream()
                .map(UiSample::id)
                .toList();
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    @Override
    protected Document loadDocument(String source) {
        UiSample sample = loadSamples().stream()
                .filter(item -> item.id().equals(source))
                .findFirst()
                .orElse(null);
        if (sample == null) {
            log.warn("UI sample not found: {}", source);
            return null;
        }

        String textContent = buildSampleText(sample);
        Map<String, Object> metadata = createMetadata(source, textContent);
        metadata.put("url", sample.githubUrl());
        metadata.put("browserUrl", sample.browserUrl());
        metadata.put("docPath", sample.path());
        metadata.put("title", sample.title());

        return createDocument(textContent, metadata);
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            List<Chunker.Chunk> chunks = chunkTextSplitter.split(new Chunker.Chunk(document.getText(), null));
            if (chunks.size() > 1) {
                log.info("Split UI sample {} into {} chunks", document.getMetadata().get("source"), chunks.size());
            }
            for (Chunker.Chunk chunk : chunks) {
                Map<String, Object> metadataCopy = new HashMap<>(document.getMetadata());
                metadataCopy.put("size", chunk.text().length());
                chunkDocs.add(createDocument(chunk.text(), metadataCopy));
            }
        }
        return chunkDocs;
    }

    private List<UiSample> loadSamples() {
        List<UiSample> samples = cachedSamples;
        if (samples != null) {
            return samples;
        }

        Map<String, String> captions = loadCaptions();
        org.jsoup.nodes.Document menu = loadMenuDocument();
        List<UiSample> result = new ArrayList<>();
        Elements rootMenus = menu.select("> menu-config > menu, > menu-config > item");
        if (rootMenus.isEmpty()) {
            rootMenus = menu.children();
        }

        for (org.jsoup.nodes.Element element : rootMenus) {
            loadMenuElement(element, List.of(), captions, result);
        }

        result.sort(Comparator.comparing(UiSample::path));
        cachedSamples = result;
        return result;
    }

    private void loadMenuElement(org.jsoup.nodes.Element element, List<String> parentPath, Map<String, String> captions, List<UiSample> samples) {
        String id = element.attr("id");
        if (StringUtils.isBlank(id)) {
            return;
        }

        String title = captions.getOrDefault(id, id);
        if ("menu".equals(element.tagName())) {
            List<String> nextPath = new ArrayList<>(parentPath);
            nextPath.add(title);
            for (org.jsoup.nodes.Element child : element.children()) {
                loadMenuElement(child, nextPath, captions, samples);
            }
            return;
        }

        if (!"item".equals(element.tagName())) {
            return;
        }

        List<String> pathParts = new ArrayList<>(parentPath);
        pathParts.add(title);

        List<Path> primaryFiles = findPrimaryFiles(id);
        List<Path> relatedFiles = new ArrayList<>(primaryFiles);
        relatedFiles.addAll(findOtherFiles(element));
        relatedFiles = relatedFiles.stream()
                .filter(Files::exists)
                .distinct()
                .sorted()
                .toList();

        Path mainPath = !primaryFiles.isEmpty()
                ? primaryFiles.get(0).getParent()
                : (!relatedFiles.isEmpty() ? relatedFiles.get(0).getParent() : effectiveLocalPath());

        samples.add(new UiSample(
                id,
                title,
                String.join(" > ", pathParts),
                appendSampleId(browserBaseUrl, id),
                toGithubUrl(mainPath),
                relatedFiles
        ));
    }

    private List<Path> findPrimaryFiles(String sampleId) {
        Path effectiveLocalPath = effectiveLocalPath();
        try (Stream<Path> walk = Files.walk(effectiveLocalPath.resolve(SCREEN_RESOURCES))) {
            List<Path> matched = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(sampleId))
                    .sorted()
                    .toList();
            if (matched.isEmpty()) {
                return List.of();
            }

            Path resourceDir = matched.get(0).getParent();
            List<Path> resourceFiles = Files.list(resourceDir)
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();

            Path relativeDir = effectiveLocalPath.resolve(SCREEN_RESOURCES).relativize(resourceDir);
            Path javaDir = effectiveLocalPath.resolve(SCREEN_JAVA).resolve(relativeDir);
            if (Files.isDirectory(javaDir)) {
                try (Stream<Path> javaWalk = Files.list(javaDir)) {
                    List<Path> javaFiles = javaWalk.filter(Files::isRegularFile)
                            .sorted()
                            .toList();
                    List<Path> all = new ArrayList<>(resourceFiles);
                    all.addAll(javaFiles);
                    return all;
                }
            }
            return resourceFiles;
        } catch (IOException e) {
            throw new RuntimeException("Failed to discover files for UI sample: " + sampleId, e);
        }
    }

    private List<Path> findOtherFiles(org.jsoup.nodes.Element itemElement) {
        List<Path> files = new ArrayList<>();
        for (org.jsoup.nodes.Element fileElement : itemElement.select("otherFiles > file")) {
            String name = fileElement.attr("name");
            if (StringUtils.isBlank(name)) {
                continue;
            }
            Path effectiveLocalPath = effectiveLocalPath();
            files.add(effectiveLocalPath.resolve("src/main/java").resolve(name));
            files.add(effectiveLocalPath.resolve("src/main/resources").resolve(name));
            files.add(effectiveLocalPath.resolve("src/main/themes").resolve(name));
        }
        return files.stream().filter(Files::exists).toList();
    }

    private String buildSampleText(UiSample sample) {
        StringBuilder sb = new StringBuilder();
        sb.append("Path: ").append(sample.path()).append("\n\n");
        sb.append("Title: ").append(sample.title()).append("\n");
        sb.append("Sample ID: ").append(sample.id()).append("\n");
        sb.append("Browser URL: ").append(sample.browserUrl()).append("\n");
        sb.append("Repository URL: ").append(sample.githubUrl()).append("\n");

        for (Path file : sample.files()) {
            String content = readFile(file);
            if (StringUtils.isBlank(content)) {
                continue;
            }
            sb.append("\n\n=== File: ").append(effectiveLocalPath().relativize(file)).append(" ===\n\n");
            sb.append(content);
        }
        return sb.toString();
    }

    private String readFile(Path file) {
        try {
            String content = Files.readString(file);
            String fileName = file.getFileName().toString();
            if (fileName.endsWith(".html")) {
                return Jsoup.parse(content).text();
            }
            return content;
        } catch (IOException e) {
            log.warn("Failed to read UI sample file: {}", file, e);
            return "";
        }
    }

    private org.jsoup.nodes.Document loadMenuDocument() {
        try {
            return Jsoup.parse(effectiveLocalPath().resolve(MENU_CONFIG).toFile(), "UTF-8", "", Parser.xmlParser());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read UI samples menu config", e);
        }
    }

    private Map<String, String> loadCaptions() {
        Properties properties = new Properties();
        try (StringReader reader = new StringReader(Files.readString(effectiveLocalPath().resolve(MESSAGES_EN)))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load UI samples captions", e);
        }

        return properties.entrySet().stream()
                .map(entry -> Map.entry(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
                .filter(entry -> entry.getKey().startsWith("sampler-menu-config."))
                .collect(Collectors.toMap(
                        entry -> entry.getKey().substring("sampler-menu-config.".length()),
                        Map.Entry::getValue
                ));
    }

    private String toGithubUrl(Path path) {
        Path relativePath = effectiveLocalPath().relativize(path);
        return appendPath(githubBaseUrl, relativePath.toString().replace('\\', '/'));
    }

    private String appendPath(String base, String suffix) {
        String normalizedBase = StringUtils.removeEnd(base, "/");
        String normalizedSuffix = StringUtils.removeStart(suffix, "/");
        return normalizedBase + "/" + normalizedSuffix;
    }

    private String appendSampleId(String base, String sampleId) {
        if (base.endsWith("=") || base.endsWith("/")) {
            return base + sampleId;
        }
        return base + "/" + sampleId;
    }

    private void validateRepositoryLayout() {
        Path effectiveLocalPath = effectiveLocalPath();
        List<Path> requiredPaths = List.of(
                effectiveLocalPath.resolve(MENU_CONFIG),
                effectiveLocalPath.resolve(MESSAGES_EN),
                effectiveLocalPath.resolve(SCREEN_RESOURCES),
                effectiveLocalPath.resolve(SCREEN_JAVA)
        );
        List<Path> missingPaths = requiredPaths.stream()
                .filter(Files::notExists)
                .toList();
        if (!missingPaths.isEmpty()) {
            String missing = missingPaths.stream()
                    .map(effectiveLocalPath::relativize)
                    .map(Path::toString)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("UI samples repository is incomplete at " + effectiveLocalPath
                    + ". Missing paths: " + missing
                    + ". Expected a checkout of https://github.com/jmix-framework/jmix-ui-samples.git");
        }
    }

    private Path effectiveLocalPath() {
        String location = getKnowledgeSourceLocation();
        return location != null ? Path.of(location) : localPath;
    }

    private record UiSample(
            String id,
            String title,
            String path,
            String browserUrl,
            String githubUrl,
            List<Path> files
    ) {
    }
}
