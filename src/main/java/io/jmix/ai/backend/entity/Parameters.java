package io.jmix.ai.backend.entity;

import io.jmix.core.MetadataTools;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.env.Environment;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Table(name = "PARAMETERS")
@Entity
public class Parameters {
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
    @Column(name = "CREATED_BY")
    private String createdBy;

    @LastModifiedDate
    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    @LastModifiedBy
    @Column(name = "LAST_MODIFIED_BY")
    private String lastModifiedBy;

    @Column(name = "ACTIVE")
    private Boolean active;

    @NotBlank
    @Column(name = "DESCRIPTION")
    private String description;

    @Column(name = "SYSTEM_MESSAGE")
    @Lob
    private String systemMessage;

    @Column(name = "USE_TOOLS")
    private Boolean useTools;

    @Column(name = "SIMILARITY_THRESHOLD")
    private Double similarityThreshold;

    @Column(name = "TOP_K")
    private Integer topK;

    @Column(name = "DOCS_TOOL_DESCRIPTION", length = 500)
    private String docsToolDescription;

    @Column(name = "DOCS_TOOL_SIMILARITY_THRESHOLD")
    private Double docsToolSimilarityThreshold;

    @Column(name = "DOCS_TOOL_TOP_K")
    private Integer docsToolTopK;

    @Column(name = "UISAMPLES_TOOL_DESCRIPTION", length = 500)
    private String uiSamplesToolDescription;

    @Column(name = "UISAMPLES_TOOL_SIMILARITY_THRESHOLD")
    private Double uiSamplesToolSimilarityThreshold;

    @Column(name = "UISAMPLES_TOOL_TOP_K")
    private Integer uiSamplesToolTopK;

    @Column(name = "TRAININGS_TOOL_DESCRIPTION", length = 500)
    private String trainingsToolDescription;

    @Column(name = "TRAININGS_TOOL_SIMILARITY_THRESHOLD")
    private Double trainingsToolSimilarityThreshold;

    @Column(name = "TRAININGS_TOOL_TOP_K")
    private Integer trainingsToolTopK;

    public Boolean getUseTools() {
        return useTools;
    }

    public void setUseTools(Boolean useTools) {
        this.useTools = useTools;
    }

    public String getDocsToolDescription() {
        return docsToolDescription;
    }

    public void setDocsToolDescription(String docsToolDescription) {
        this.docsToolDescription = docsToolDescription;
    }

    public Double getDocsToolSimilarityThreshold() {
        return docsToolSimilarityThreshold;
    }

    public void setDocsToolSimilarityThreshold(Double docsToolSimilarityThreshold) {
        this.docsToolSimilarityThreshold = docsToolSimilarityThreshold;
    }

    public Integer getDocsToolTopK() {
        return docsToolTopK;
    }

    public void setDocsToolTopK(Integer docsToolTopK) {
        this.docsToolTopK = docsToolTopK;
    }

    public String getUiSamplesToolDescription() {
        return uiSamplesToolDescription;
    }

    public void setUiSamplesToolDescription(String uiSamplesToolDescription) {
        this.uiSamplesToolDescription = uiSamplesToolDescription;
    }

    public Double getUiSamplesToolSimilarityThreshold() {
        return uiSamplesToolSimilarityThreshold;
    }

    public void setUiSamplesToolSimilarityThreshold(Double uiSamplesToolSimilarityThreshold) {
        this.uiSamplesToolSimilarityThreshold = uiSamplesToolSimilarityThreshold;
    }

    public Integer getUiSamplesToolTopK() {
        return uiSamplesToolTopK;
    }

    public void setUiSamplesToolTopK(Integer uiSamplesToolTopK) {
        this.uiSamplesToolTopK = uiSamplesToolTopK;
    }

    public String getTrainingsToolDescription() {
        return trainingsToolDescription;
    }

    public void setTrainingsToolDescription(String trainingsToolDescription) {
        this.trainingsToolDescription = trainingsToolDescription;
    }

    public Double getTrainingsToolSimilarityThreshold() {
        return trainingsToolSimilarityThreshold;
    }

    public void setTrainingsToolSimilarityThreshold(Double trainingsToolSimilarityThreshold) {
        this.trainingsToolSimilarityThreshold = trainingsToolSimilarityThreshold;
    }

    public Integer getTrainingsToolTopK() {
        return trainingsToolTopK;
    }

    public void setTrainingsToolTopK(Integer trainingsToolTopK) {
        this.trainingsToolTopK = trainingsToolTopK;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(Double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
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

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(String systemMessage) {
        this.systemMessage = systemMessage;
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

    @InstanceName
    @DependsOnProperties({"description", "lastModifiedDate", "lastModifiedBy"})
    public String getInstanceName(MetadataTools metadataTools, DatatypeFormatter datatypeFormatter) {
        return String.format("%s, modified at %s by %s",
                StringUtils.isBlank(description) ? "no description" : description,
                datatypeFormatter.formatOffsetDateTime(lastModifiedDate),
                metadataTools.format(lastModifiedBy));
    }

    @PostConstruct
    public void init(Environment environment) {
        useTools = true;

        docsToolDescription = environment.getProperty("docs.tool.description");
        docsToolSimilarityThreshold = Double.parseDouble(environment.getProperty("docs.tool.similarity-threshold", "0.0"));
        docsToolTopK = Integer.parseInt(environment.getProperty("docs.tool.top-k", "4"));

        uiSamplesToolDescription = environment.getProperty("ui-samples.tool.description");
        uiSamplesToolSimilarityThreshold = Double.parseDouble(environment.getProperty("ui-samples.tool.similarity-threshold", "0.0"));
        uiSamplesToolTopK = Integer.parseInt(environment.getProperty("ui-samples.tool.top-k", "4"));

        trainingsToolDescription = environment.getProperty("trainings.tool.description");
        trainingsToolSimilarityThreshold = Double.parseDouble(environment.getProperty("trainings.tool.similarity-threshold", "0.0"));
        trainingsToolTopK = Integer.parseInt(environment.getProperty("trainings.tool.top-k", "4"));
    }
}