package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Table(name = "KNOWLEDGE_SOURCE", indexes = {
        @Index(name = "IDX_KNOWLEDGE_SOURCE_KB", columnList = "KNOWLEDGE_BASE_ID"),
        @Index(name = "IDX_KNOWLEDGE_SOURCE_CODE", columnList = "CODE")
}, uniqueConstraints = {
        @UniqueConstraint(name = "IDX_KNOWLEDGE_SOURCE_UNQ_KB_CODE", columnNames = {"KNOWLEDGE_BASE_ID", "CODE"})
})
@Entity
public class KnowledgeSource {

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Column(name = "VERSION", nullable = false)
    @Version
    private Integer version;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @CreatedBy
    @Column(name = "CREATED_BY", length = 255)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    @LastModifiedBy
    @Column(name = "LAST_MODIFIED_BY", length = 255)
    private String lastModifiedBy;

    @JoinColumn(name = "KNOWLEDGE_BASE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private KnowledgeBase knowledgeBase;

    @InstanceName
    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    @Column(name = "CODE", nullable = false, length = 255)
    private String code;

    @Column(name = "SOURCE_TYPE", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "LOCATION_", nullable = false, length = 1000)
    private String location;

    @Column(name = "LANGUAGE_", length = 50)
    private String language;

    @Column(name = "ENABLED_")
    private Boolean enabled;

    @Column(name = "UPDATE_MODE", length = 50)
    private String updateMode;

    @Column(name = "SETTINGS_JSON", columnDefinition = "CLOB")
    private String settingsJson;

    @Column(name = "LAST_SUCCESSFUL_INGESTION_AT")
    private OffsetDateTime lastSuccessfulIngestionAt;

    public OffsetDateTime getLastSuccessfulIngestionAt() {
        return lastSuccessfulIngestionAt;
    }

    public void setLastSuccessfulIngestionAt(OffsetDateTime lastSuccessfulIngestionAt) {
        this.lastSuccessfulIngestionAt = lastSuccessfulIngestionAt;
    }

    public String getSettingsJson() {
        return settingsJson;
    }

    public void setSettingsJson(String settingsJson) {
        this.settingsJson = settingsJson;
    }

    public KnowledgeSourceUpdateMode getUpdateMode() {
        return updateMode == null ? null : KnowledgeSourceUpdateMode.fromId(updateMode);
    }

    public void setUpdateMode(KnowledgeSourceUpdateMode updateMode) {
        this.updateMode = updateMode == null ? null : updateMode.getId();
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public KnowledgeSourceType getSourceType() {
        return sourceType == null ? null : KnowledgeSourceType.fromId(sourceType);
    }

    public void setSourceType(KnowledgeSourceType sourceType) {
        this.sourceType = sourceType == null ? null : sourceType.getId();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public void setKnowledgeBase(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public OffsetDateTime getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
}
