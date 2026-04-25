package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;

import java.util.UUID;

@JmixEntity(name = "ai_KnowledgeDocumentItem")
public class KnowledgeDocumentItem {

    @JmixGeneratedValue
    @JmixId
    private UUID id;

    private UUID knowledgeSourceId;

    private String sourceName;

    @InstanceName
    private String documentPath;

    private String documentName;

    private String documentKind;

    private String sourceType;

    private Integer chunkCount;

    private UUID representativeVectorStoreId;

    private String externalUrl;

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl;
    }

    public UUID getRepresentativeVectorStoreId() {
        return representativeVectorStoreId;
    }

    public void setRepresentativeVectorStoreId(UUID representativeVectorStoreId) {
        this.representativeVectorStoreId = representativeVectorStoreId;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getDocumentKind() {
        return documentKind;
    }

    public void setDocumentKind(String documentKind) {
        this.documentKind = documentKind;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getDocumentPath() {
        return documentPath;
    }

    public void setDocumentPath(String documentPath) {
        this.documentPath = documentPath;
    }

    public UUID getKnowledgeSourceId() {
        return knowledgeSourceId;
    }

    public void setKnowledgeSourceId(UUID knowledgeSourceId) {
        this.knowledgeSourceId = knowledgeSourceId;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
}
