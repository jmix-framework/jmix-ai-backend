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
 * Persistent cache of LLM-generated <em>enrichment</em> for ingested sources, so that
 * re-ingestion regenerates only new or changed sources instead of re-calling the model for the
 * whole corpus (the Java API corpus alone is the entire Jmix Javadoc — thousands of pages, each an
 * LLM call).
 * <p>
 * A row is addressed by the logical key {@code (type, source, jmixVersion, modelName)} — the unique
 * index {@code IDX_ENRICHMENT_CACHE_LOOKUP} — where {@link #modelName} is not the bare model name
 * but the enricher key (model + reasoning effort + prompt version; see the field and "Generations"
 * below). A key hit is reused only while {@link #contentHash} still matches the current source
 * content; otherwise the source is re-enriched and the row updated. The read/write path (and the
 * concurrent-insert race handling) lives in {@code EnrichmentCacheRepository}; generation of the
 * enrichment itself lives in the {@code JavaApiEnricher} / {@code SnippetizerEnricher}.
 * <p>
 * <strong>Generations.</strong> Because {@link #modelName} encodes the model, the reasoning effort
 * <em>and</em> the prompt version (not just the model name), changing any of them yields a new key
 * and therefore a new "generation" of rows that coexists with the old one. Bumping the enricher's
 * prompt version is exactly how a prompt edit is made to invalidate stale cached output.
 * {@code EnrichmentCacheCleanupService} keeps the active and the immediately previous generation
 * per {@code (type, jmixVersion)} scope (ordered by {@link #createdDate}, newest first) and deletes
 * older ones, so the table stays bounded across prompt/model iterations.
 * <p>
 * Deliberately opts out of the project's {@code @Version}/{@code @InstanceName} entity baseline:
 * rows are cache entries with no UI representation, concurrent inserts are resolved through the
 * unique key, and last-write-wins is the intended semantics for concurrent updates.
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

    /** Insert timestamp; the cleanup service orders generations by it (newest first). */
    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    /** Corpus this entry belongs to: {@code javaapi-enriched}, {@code docs-snippets} or {@code uisamples-snippets}. */
    @Column(name = "TYPE_", nullable = false)
    private String type;

    /** Source identifier within the corpus, e.g. the Javadoc page path {@code io/jmix/core/DataManager.html}. */
    @Column(name = "SOURCE", nullable = false, length = 1000)
    private String source;

    /** Jmix version of the source ({@code v2}/{@code v3}); required. The same source in different versions is cached separately. */
    @Column(name = "JMIX_VERSION", nullable = false, length = 10)
    private String jmixVersion;

    /**
     * The enricher's {@code getModelKey()}: model name, reasoning effort <em>and</em> prompt
     * version combined (e.g. {@code gpt-5.4-nano:low:prompt-version-2026-07-26}) — despite the column name, NOT just the
     * model. Part of the cache key, so bumping the prompt version or switching the model produces
     * a new generation rather than overwriting existing rows.
     */
    @Column(name = "MODEL_NAME", nullable = false)
    private String modelName;

    /**
     * murmur3_32 hash of the ingested <em>content</em> — the document text ({@code sourceHash}
     * metadata: the formatted API card for {@code javaapi-enriched}, the page text for the snippet corpuses),
     * NOT of the {@link #source} identifier. A key hit is reused only while this still matches the
     * current content, so a changed page is re-enriched even under an unchanged key; conversely a
     * cosmetic change that leaves the rendered content identical keeps the hash and is skipped.
     */
    @Column(name = "CONTENT_HASH", nullable = false)
    private String contentHash;

    /**
     * Serialized generated payload; its format depends on {@link #type}: the JSON-serialized list
     * of {@code Snippet}s for the snippet corpuses ({@code docs-snippets}/{@code uisamples-snippets}),
     * the JSON-serialized {@code Enrichment} (description and usage example) for {@code javaapi-enriched}.
     * An unreadable payload is treated as a cache miss and regenerated.
     */
    @Column(name = "CONTENT")
    @Lob
    private String content;

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

    public JmixVersion getJmixVersion() {
        return jmixVersion == null ? null : JmixVersion.fromId(jmixVersion);
    }

    public void setJmixVersion(JmixVersion jmixVersion) {
        this.jmixVersion = jmixVersion == null ? null : jmixVersion.getId();
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
