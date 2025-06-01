package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.TimeSource;
import io.jmix.core.UuidProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class UiSamplesRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(UiSamplesRetriever.class);

    private final String baseUrl;
    private final String docPath;
    private final String samplePath;
    private final int limitSamples;
    private final VectorStore vectorStore;
    private final TimeSource timeSource;
    private final VectorStoreRepository vectorStoreRepository;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UiSamplesRetriever(
            @Value( "${uisamples.base-url}") String baseUrl,
            @Value( "${uisamples.doc-path}") String docPath,
            @Value( "${uisamples.sample-path}") String samplePath,
            @Value( "${uisamples.limit-samples}") int limitSamples,
            VectorStore vectorStore,
            TimeSource timeSource,
            VectorStoreRepository vectorStoreRepository) {
        this.baseUrl = baseUrl;
        this.docPath = docPath;
        this.samplePath = samplePath;
        this.limitSamples = limitSamples;
        this.vectorStore = vectorStore;
        this.timeSource = timeSource;
        this.vectorStoreRepository = vectorStoreRepository;
    }

    @Override
    public String getType() {
        return "uisamples";
    }

    @Override
    public String updateAll() {
        long start = timeSource.currentTimeMillis();

        List<String> samples = loadListOfSampleIds();
        log.info("Found {} samples, loading {}", samples.size(), limitSamples > 0 ? "first " + limitSamples : "all");

        List<org.springframework.ai.document.Document> documents = samples.stream()
                .limit(limitSamples > 0 ? limitSamples : samples.size())
                .map(sampleId -> loadSample(sampleId))
                .filter(this::checkContent)
                .toList();

        log.info("Adding {} documents to vector store", documents.size());
        vectorStore.add(documents);

        log.info("Done in {} sec", (timeSource.currentTimeMillis() - start) / 1000.0);

        return "loaded: %d, added: %d".formatted(samples.size(), documents.size());
    }

    @Override
    public String update(VectorStoreEntity entity) {
        String sampleId = (String) entity.getMetadataMap().get("sampleId");
        String url = getDocUrl(sampleId);
        log.info("Loading sample: {}", url);
        org.springframework.ai.document.Document document = loadSample(sampleId);
        if (!entity.getContent().equals(document.getText())) {
            vectorStoreRepository.delete(entity.getId());
            log.info("Adding document to vector store");
            vectorStore.add(List.of(document));
            return "updated 1 document";
        } else {
            return "no changes";
        }
    }

    private boolean checkContent(org.springframework.ai.document.Document document) {
        String sampleId = (String) document.getMetadata().get("sampleId");

        List<VectorStoreEntity> entities = vectorStoreRepository.loadList(
                "type == '%s' && sampleId == '%s'".formatted(getType(), sampleId)
        );
        if (entities.isEmpty()) {
            return true;
        }
        boolean contentChanged = false;
        for (VectorStoreEntity entity : entities) {
            if (!entity.getContent().equals(document.getText())) {
                // Delete existing document since content has changed
                vectorStoreRepository.delete(entity.getId());
                contentChanged = true;
            }
        }
        return contentChanged;
    }

    private List<String> loadListOfSampleIds() {
        String url = baseUrl + "/" + docPath;
        try {
            String jsonResponse = restTemplate.getForObject(url, String.class);
            List<String> list = objectMapper.readValue(jsonResponse, new TypeReference<>() { });
            return list;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load doc IDs from: " + url, e);
        }
    }

    private org.springframework.ai.document.Document loadSample(String sampleId) {
        String url = getDocUrl(sampleId);
        log.debug("Loading sample: {}", url);

        String textContent = restTemplate.getForObject(url, String.class);

        return new org.springframework.ai.document.Document(
                UuidProvider.createUuidV7().toString(),
                textContent,
                createMetadata(sampleId, textContent)
        );
    }

    private Map<String, Object> createMetadata(String sampleId, String textContent) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", getType());
        metadata.put("url", getSampleUrl(sampleId));
        metadata.put("docUrl", getDocUrl(sampleId));
        metadata.put("sampleId", sampleId);
        metadata.put("size", textContent.length());
        metadata.put("updated", timeSource.now().toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return metadata;
    }

    private String getDocUrl(String sampleId) {
        return baseUrl + "/" + docPath + "/" + sampleId;
    }

    private String getSampleUrl(String sampleId) {
        return baseUrl + "/" + samplePath + "/" + sampleId;
    }
}
