package io.jmix.ai.backend.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.JmixProperty;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@JmixEntity
@Table(name = "PARAMETERS")
@Entity
public class Parameters {

    public static final String NO_DESCRIPTION = "<no description>";

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

    @Column(name = "CONTENT")
    @Lob
    private String content;

    @Column(name = "TARGET_TYPE")
    private String targetType = ParametersTargetType.CHAT.getId();

    public ParametersTargetType getTargetType() {
        return targetType == null ? null : ParametersTargetType.fromId(targetType);
    }

    public void setTargetType(ParametersTargetType targetType) {
        this.targetType = targetType == null ? null : targetType.getId();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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

    @JmixProperty
    @DependsOnProperties({"content"})
    public String getDescription() {
        if (getContent() == null) {
            return NO_DESCRIPTION;
        }
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            //noinspection unchecked
            Map<String, Object> data = mapper.readValue(getContent(), Map.class);
            return data.get("description") != null ? data.get("description").toString() : NO_DESCRIPTION;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
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
                getDescription(),
                datatypeFormatter.formatOffsetDateTime(lastModifiedDate),
                metadataTools.format(lastModifiedBy));
    }

}