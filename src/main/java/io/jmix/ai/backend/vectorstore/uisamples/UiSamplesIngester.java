package io.jmix.ai.backend.vectorstore.uisamples;

import io.jmix.ai.backend.vectorstore.AbstractIngester;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class UiSamplesIngester extends AbstractIngester {

    private static final Logger log = LoggerFactory.getLogger(UiSamplesIngester.class);

    private final String baseUrl;
    private final String docPath;
    private final String samplePath;
    private final int limit;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UiSamplesIngester(
            @Value("${uisamples.base-url}") String baseUrl,
            @Value("${uisamples.doc-path}") String docPath,
            @Value("${uisamples.sample-path}") String samplePath,
            @Value("${uisamples.limit}") int limit,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        super(vectorStore, timeSource, vectorStoreRepository);
        this.baseUrl = baseUrl;
        this.docPath = docPath;
        this.samplePath = samplePath;
        this.limit = limit;
    }

    @Override
    public String getType() {
        return "uisamples";
    }

    @Override
    protected List<String> loadSources() {
        String url = baseUrl + "/" + docPath;
        try {
            String jsonResponse = restTemplate.getForObject(url, String.class);
            return objectMapper.readValue(jsonResponse, new TypeReference<>() { });
        } catch (Exception e) {
            throw new RuntimeException("Failed to load doc IDs from: " + url, e);
        }
    }

    @Override
    protected int getSourceLimit() {
        return limit;
    }

    @Override
    protected Document loadDocument(String source) {
        String url = getDocUrl(source);
        log.debug("Loading sample: {}", url);

        String textContent = restTemplate.getForObject(url, String.class);

        Map<String, Object> metadata = createMetadata(source, textContent);
        metadata.put("url", getSampleUrl(source));
        metadata.put("docUrl", url);

        return createDocument(textContent, metadata);
    }

    private String getDocUrl(String sampleId) {
        return baseUrl + "/" + docPath + "/" + sampleId;
    }

    private String getSampleUrl(String sampleId) {
        return baseUrl + "/" + samplePath + "/" + sampleId;
    }

    @Override
    protected List<Document> splitToChunks(List<Document> documents) {
        List<Document> chunkDocs = new ArrayList<>();
        for (Document document : documents) {
            if (document.getText().length() <= MAX_CHUNK_SIZE) {
                chunkDocs.add(document);
            } else {
                log.warn("Document {} is too long: {}", document.getMetadata().get("url"), document.getText().length());
            }
        }
        return chunkDocs;
    }
}
