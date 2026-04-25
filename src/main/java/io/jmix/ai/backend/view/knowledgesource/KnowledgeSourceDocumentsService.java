package io.jmix.ai.backend.view.knowledgesource;

import io.jmix.ai.backend.entity.KnowledgeDocumentItem;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.DataManager;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class KnowledgeSourceDocumentsService {

    private final DataManager dataManager;
    private final VectorStoreRepository vectorStoreRepository;
    private final IngesterManager ingesterManager;

    public KnowledgeSourceDocumentsService(DataManager dataManager,
                                           VectorStoreRepository vectorStoreRepository,
                                           IngesterManager ingesterManager) {
        this.dataManager = dataManager;
        this.vectorStoreRepository = vectorStoreRepository;
        this.ingesterManager = ingesterManager;
    }

    public KnowledgeSource loadSource(UUID sourceId) {
        return dataManager.load(KnowledgeSource.class)
                .id(sourceId)
                .one();
    }

    public List<KnowledgeDocumentItem> loadDocuments(UUID sourceId) {
        KnowledgeSource source = loadSource(sourceId);
        List<VectorStoreEntity> chunks = loadChunksForSource(source);
        Map<String, DocumentAccumulator> byDocument = new LinkedHashMap<>();

        for (VectorStoreEntity chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadataMap();
            String documentPath = firstNonBlank(
                    asString(metadata.get("documentPath")),
                    asString(metadata.get("docPath")),
                    asString(metadata.get("source"))
            );
            if (documentPath == null) {
                continue;
            }
            String documentName = firstNonBlank(
                    asString(metadata.get("documentName")),
                    fileNameOf(documentPath),
                    documentPath
            );
            String documentKind = firstNonBlank(asString(metadata.get("documentKind")), "document");
            String sourceType = asString(metadata.get("type"));
            String externalUrl = firstNonBlank(asString(metadata.get("browserUrl")), asString(metadata.get("url")));

            byDocument.computeIfAbsent(documentPath,
                            key -> new DocumentAccumulator(sourceId, documentPath, documentName, documentKind, sourceType,
                                    chunk.getId(), externalUrl))
                    .increment();
        }

        return byDocument.values().stream()
                .map(accumulator -> {
                    KnowledgeDocumentItem item = dataManager.create(KnowledgeDocumentItem.class);
                    item.setKnowledgeSourceId(accumulator.sourceId());
                    item.setDocumentPath(accumulator.documentPath());
                    item.setDocumentName(accumulator.documentName());
                    item.setDocumentKind(accumulator.documentKind());
                    item.setSourceType(accumulator.sourceType());
                    item.setChunkCount(accumulator.chunkCount());
                    item.setRepresentativeVectorStoreId(accumulator.representativeVectorStoreId());
                    item.setExternalUrl(accumulator.externalUrl());
                    return item;
                })
                .toList();
    }

    public void reingestDocument(KnowledgeDocumentItem item) {
        VectorStoreEntity entity = vectorStoreRepository.load(item.getRepresentativeVectorStoreId());
        ingesterManager.updateByEntity(entity);
    }

    public void deleteDocument(KnowledgeDocumentItem item) {
        VectorStoreEntity representativeChunk = vectorStoreRepository.load(item.getRepresentativeVectorStoreId());
        Map<String, Object> metadata = representativeChunk.getMetadataMap();
        String sourceValue = firstNonBlank(
                asString(metadata.get("documentPath")),
                asString(metadata.get("source")),
                asString(metadata.get("docPath"))
        );
        String sourceId = asString(metadata.get("sourceId"));
        String sourceCode = asString(metadata.get("sourceCode"));
        String filter;
        if (StringUtils.isNotBlank(sourceId)) {
            filter = "sourceId == '%s' && source == '%s'".formatted(sourceId, sourceValue);
        } else if (StringUtils.isNotBlank(sourceCode)) {
            filter = "sourceCode == '%s' && source == '%s'".formatted(sourceCode, sourceValue);
        } else {
            String legacyType = asString(metadata.get("type"));
            if (StringUtils.isNotBlank(legacyType)) {
                filter = "type == '%s' && source == '%s'".formatted(legacyType, sourceValue);
            } else {
                filter = "source == '%s'".formatted(sourceValue);
            }
        }
        List<VectorStoreEntity> chunks = vectorStoreRepository.loadList(filter);
        vectorStoreRepository.delete(chunks);
    }

    private List<VectorStoreEntity> loadChunksForSource(KnowledgeSource source) {
        List<VectorStoreEntity> chunks = vectorStoreRepository.loadList("sourceId == '%s'".formatted(source.getId()));
        if (!chunks.isEmpty()) {
            return chunks;
        }
        chunks = vectorStoreRepository.loadList("sourceCode == '%s'".formatted(source.getCode()));
        if (!chunks.isEmpty()) {
            return chunks;
        }
        String legacyType = inferLegacyType(source);
        if (StringUtils.isNotBlank(legacyType)) {
            return vectorStoreRepository.loadList("type == '%s'".formatted(legacyType));
        }
        return List.of();
    }

    private String inferLegacyType(KnowledgeSource source) {
        return switch (source.getCode()) {
            case "docs-site" -> "docs";
            case "trainings-local" -> "trainings";
            case "uisamples-local" -> "uisamples";
            case "jmix-framework-code-1.7.2" -> "jmix-framework-code";
            case "business-documents-local" -> "business-documents";
            default -> null;
        };
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String fileNameOf(String documentPath) {
        int slashIndex = Math.max(documentPath.lastIndexOf('/'), documentPath.lastIndexOf('\\'));
        return slashIndex >= 0 ? documentPath.substring(slashIndex + 1) : documentPath;
    }

    private static final class DocumentAccumulator {
        private final UUID sourceId;
        private final String documentPath;
        private final String documentName;
        private final String documentKind;
        private final String sourceType;
        private final UUID representativeVectorStoreId;
        private final String externalUrl;
        private int chunkCount;

        private DocumentAccumulator(UUID sourceId, String documentPath, String documentName, String documentKind,
                                    String sourceType, UUID representativeVectorStoreId, String externalUrl) {
            this.sourceId = sourceId;
            this.documentPath = documentPath;
            this.documentName = documentName;
            this.documentKind = documentKind;
            this.sourceType = sourceType;
            this.representativeVectorStoreId = representativeVectorStoreId;
            this.externalUrl = externalUrl;
        }

        void increment() {
            chunkCount++;
        }

        UUID sourceId() {
            return sourceId;
        }

        String documentPath() {
            return documentPath;
        }

        String documentName() {
            return documentName;
        }

        String documentKind() {
            return documentKind;
        }

        String sourceType() {
            return sourceType;
        }

        Integer chunkCount() {
            return chunkCount;
        }

        UUID representativeVectorStoreId() {
            return representativeVectorStoreId;
        }

        String externalUrl() {
            return externalUrl;
        }
    }
}
