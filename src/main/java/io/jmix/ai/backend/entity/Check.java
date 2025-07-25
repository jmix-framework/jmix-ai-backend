package io.jmix.ai.backend.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Table(name = "CHECK_", indexes = {
        @Index(name = "IDX_CHECK__CHECK_DEF", columnList = "CHECK_DEF_ID"),
        @Index(name = "IDX_CHECK__CHECK_RUN", columnList = "CHECK_RUN_ID")
})
@Entity(name = "Check_")
public class Check {
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

    @JoinColumn(name = "CHECK_DEF_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private CheckDef checkDef;

    @JoinColumn(name = "CHECK_RUN_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private CheckRun checkRun;

    @Column(name = "CATEGORY")
    private String category;

    @Column(name = "QUESTION")
    @Lob
    private String question;

    @Column(name = "REFERENCE_ANSWER")
    @Lob
    private String referenceAnswer;

    @Column(name = "ACTUAL_ANSWER")
    @Lob
    private String actualAnswer;

    @Column(name = "SCRIPT")
    @Lob
    private String script;

    @Column(name = "SCRIPT_SCORE")
    private Double scriptScore;

    @Column(name = "ROUGE_SCORE")
    private Double rougeScore;

    @Column(name = "BERT_SCORE")
    private Double bertScore;

    @Column(name = "LOG")
    @Lob
    private String log;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getLog() {
        return log;
    }

    public void setLog(String log) {
        this.log = log;
    }

    public Double getBertScore() {
        return bertScore;
    }

    public void setBertScore(Double bertScore) {
        this.bertScore = bertScore;
    }

    public Double getRougeScore() {
        return rougeScore;
    }

    public void setRougeScore(Double rougeScore) {
        this.rougeScore = rougeScore;
    }

    public Double getScriptScore() {
        return scriptScore;
    }

    public void setScriptScore(Double scriptScore) {
        this.scriptScore = scriptScore;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getActualAnswer() {
        return actualAnswer;
    }

    public void setActualAnswer(String actualAnswer) {
        this.actualAnswer = actualAnswer;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public CheckRun getCheckRun() {
        return checkRun;
    }

    public void setCheckRun(CheckRun checkRun) {
        this.checkRun = checkRun;
    }

    public CheckDef getCheckDef() {
        return checkDef;
    }

    public void setCheckDef(CheckDef checkDef) {
        this.checkDef = checkDef;
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