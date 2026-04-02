package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Table(name = "INGESTION_JOB", indexes = {
        @Index(name = "IDX_INGESTION_JOB_KB", columnList = "KNOWLEDGE_BASE_ID"),
        @Index(name = "IDX_INGESTION_JOB_SOURCE", columnList = "KNOWLEDGE_SOURCE_ID"),
        @Index(name = "IDX_INGESTION_JOB_STATUS", columnList = "STATUS")
})
@Entity
public class IngestionJob {

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

    @JoinColumn(name = "KNOWLEDGE_BASE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private KnowledgeBase knowledgeBase;

    @JoinColumn(name = "KNOWLEDGE_SOURCE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private KnowledgeSource knowledgeSource;

    @Column(name = "STATUS", nullable = false, length = 50)
    private String status;

    @Column(name = "STARTED_AT")
    private OffsetDateTime startedAt;

    @Column(name = "FINISHED_AT")
    private OffsetDateTime finishedAt;

    @Column(name = "LOADED_SOURCES")
    private Integer loadedSources;

    @Column(name = "ADDED_DOCUMENTS")
    private Integer addedDocuments;

    @Column(name = "ADDED_CHUNKS")
    private Integer addedChunks;

    @Column(name = "MESSAGE_", length = 4000)
    private String message;

    @Column(name = "ERROR_DETAILS", length = 4000)
    private String errorDetails;

    public String getErrorDetails() {
        return errorDetails;
    }

    public void setErrorDetails(String errorDetails) {
        this.errorDetails = errorDetails;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getAddedChunks() {
        return addedChunks;
    }

    public void setAddedChunks(Integer addedChunks) {
        this.addedChunks = addedChunks;
    }

    public Integer getAddedDocuments() {
        return addedDocuments;
    }

    public void setAddedDocuments(Integer addedDocuments) {
        this.addedDocuments = addedDocuments;
    }

    public Integer getLoadedSources() {
        return loadedSources;
    }

    public void setLoadedSources(Integer loadedSources) {
        this.loadedSources = loadedSources;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public IngestionJobStatus getStatus() {
        return status == null ? null : IngestionJobStatus.fromId(status);
    }

    public void setStatus(IngestionJobStatus status) {
        this.status = status == null ? null : status.getId();
    }

    public KnowledgeSource getKnowledgeSource() {
        return knowledgeSource;
    }

    public void setKnowledgeSource(KnowledgeSource knowledgeSource) {
        this.knowledgeSource = knowledgeSource;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public void setKnowledgeBase(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
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
