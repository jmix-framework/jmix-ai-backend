package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Table(name = "CHECK_RUN")
@Entity
public class CheckRun {
    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @CreatedBy
    @Column(name = "CREATED_BY")
    private String createdBy;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @Column(name = "PARAMETERS")
    @Lob
    private String parameters;

    @Column(name = "SCRIPT_SCORE")
    private Double scriptScore;

    @Column(name = "SEMANTIC_SCORE")
    private Double semanticScore;

    public Double getSemanticScore() {
        return semanticScore;
    }

    public void setSemanticScore(Double semanticScore) {
        this.semanticScore = semanticScore;
    }

    public Double getScriptScore() {
        return scriptScore;
    }

    public void setScriptScore(Double scriptScore) {
        this.scriptScore = scriptScore;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
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

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

}