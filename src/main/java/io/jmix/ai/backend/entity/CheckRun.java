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

    @Column(name = "JMIX_VERSION")
    private String jmixVersion = JmixVersion.V2.getId();

    @Column(name = "SCORE")
    private Double score;

    @Column(name = "ACCURACY")
    private Double accuracy;

    @Column(name = "CONFIG_LABEL")
    private String configLabel;

    @Column(name = "EVALUATOR_CONFIG")
    private String evaluatorConfig;

    @Column(name = "DEFINITION_FINGERPRINT")
    private String definitionFingerprint;

    public String getDefinitionFingerprint() {
        return definitionFingerprint;
    }

    public void setDefinitionFingerprint(String definitionFingerprint) {
        this.definitionFingerprint = definitionFingerprint;
    }

    public String getEvaluatorConfig() {
        return evaluatorConfig;
    }

    public void setEvaluatorConfig(String evaluatorConfig) {
        this.evaluatorConfig = evaluatorConfig;
    }

    public JmixVersion getJmixVersion() {
        return jmixVersion == null ? null : JmixVersion.fromId(jmixVersion);
    }

    public void setJmixVersion(JmixVersion jmixVersion) {
        this.jmixVersion = jmixVersion == null ? null : jmixVersion.getId();
    }

    public String getConfigLabel() {
        return configLabel;
    }

    public void setConfigLabel(String configLabel) {
        this.configLabel = configLabel;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(Double accuracy) {
        this.accuracy = accuracy;
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
