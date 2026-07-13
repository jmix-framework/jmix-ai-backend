package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Caches LLM-generated enrichment for an ingested source, keyed by the hash of the deterministic
 * source content, so that re-ingestion does not re-generate unchanged sources.
 */
@JmixEntity
@Table(name = "ENRICHMENT_CACHE", indexes = {
        @Index(name = "IDX_ENRICHMENT_CACHE_LOOKUP", columnList = "TYPE_, SOURCE, JMIX_VERSION, MODEL_NAME", unique = true)
})
@Entity
public class EnrichmentCache {
    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @Column(name = "TYPE_", nullable = false)
    private String type;

    @Column(name = "SOURCE", nullable = false, length = 1000)
    private String source;

    @Column(name = "JMIX_VERSION", length = 10)
    private String jmixVersion;

    @Column(name = "MODEL_NAME", nullable = false)
    private String modelName;

    @Column(name = "CONTENT_HASH", nullable = false)
    private String contentHash;

    @Column(name = "DESCRIPTION")
    @Lob
    private String description;

    @Column(name = "EXAMPLE")
    @Lob
    private String example;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getJmixVersion() {
        return jmixVersion;
    }

    public void setJmixVersion(String jmixVersion) {
        this.jmixVersion = jmixVersion;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }
}
