package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Table(name = "CHAT_LOG", indexes = {
        @Index(name = "IDX_CHAT_LOG_DATE", columnList = "CREATED_DATE"),
        @Index(name = "IDX_CHAT_LOG_CONVERSATION_ID", columnList = "CONVERSATION_ID")
})
@Entity
public class ChatLog {
    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @Column(name = "CONVERSATION_ID")
    private String conversationId;

    @Column(name = "CONTENT")
    @Lob
    private String content;

    @Column(name = "SOURCES")
    @Lob
    private String sources;

    @Column(name = "PROMPT_TOKENS")
    private Integer promptTokens;

    @Column(name = "COMPLETION_TOKENS")
    private Integer completionTokens;

    @Column(name = "RESPONSE_TIME")
    private Integer responseTime;

    public Integer getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(Integer responseTime) {
        this.responseTime = responseTime;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public String getSources() {
        return sources;
    }

    public void setSources(String sources) {
        this.sources = sources;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

}