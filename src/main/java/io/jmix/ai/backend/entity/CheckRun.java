package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(name = "SCORE")
    private Double score;

    @Column(name = "DATASET_VERSION")
    private String datasetVersion;

    @Column(name = "ANSWER_MODEL")
    private String answerModel;

    @Column(name = "EVALUATOR_MODEL")
    private String evaluatorModel;

    @Column(name = "EVALUATOR_ENDPOINT")
    private String evaluatorEndpoint;

    @Column(name = "EXPERIMENT_KEY")
    private String experimentKey;

    @Column(name = "GOLDEN_ONLY")
    private Boolean goldenOnly;

    @Column(name = "RETRIEVAL_PROFILE")
    private String retrievalProfile;

    @Column(name = "PROMPT_REVISION")
    private String promptRevision;

    @Column(name = "KNOWLEDGE_SNAPSHOT")
    private String knowledgeSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS")
    private CheckRunStatus status;

    @Column(name = "FAILURE_REASON")
    @Lob
    private String failureReason;

    @Column(name = "NOTES")
    @Lob
    private String notes;

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Boolean getGoldenOnly() {
        return goldenOnly;
    }

    public void setGoldenOnly(Boolean goldenOnly) {
        this.goldenOnly = goldenOnly;
    }

    public String getRetrievalProfile() {
        return retrievalProfile;
    }

    public void setRetrievalProfile(String retrievalProfile) {
        this.retrievalProfile = retrievalProfile;
    }

    public String getPromptRevision() {
        return promptRevision;
    }

    public void setPromptRevision(String promptRevision) {
        this.promptRevision = promptRevision;
    }

    public String getKnowledgeSnapshot() {
        return knowledgeSnapshot;
    }

    public void setKnowledgeSnapshot(String knowledgeSnapshot) {
        this.knowledgeSnapshot = knowledgeSnapshot;
    }

    public String getExperimentKey() {
        return experimentKey;
    }

    public void setExperimentKey(String experimentKey) {
        this.experimentKey = experimentKey;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public CheckRunStatus getStatus() {
        return status;
    }

    public void setStatus(CheckRunStatus status) {
        this.status = status;
    }

    public String getEvaluatorEndpoint() {
        return evaluatorEndpoint;
    }

    public void setEvaluatorEndpoint(String evaluatorEndpoint) {
        this.evaluatorEndpoint = evaluatorEndpoint;
    }

    public String getEvaluatorModel() {
        return evaluatorModel;
    }

    public void setEvaluatorModel(String evaluatorModel) {
        this.evaluatorModel = evaluatorModel;
    }

    public String getAnswerModel() {
        return answerModel;
    }

    public void setAnswerModel(String answerModel) {
        this.answerModel = answerModel;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
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
