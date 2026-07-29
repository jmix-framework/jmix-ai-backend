package io.jmix.ai.backend.vectorstore.javaapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.lang.Nullable;

/**
 * LLM-generated addition to a Java API card: a written description and a usage example. Produced
 * by {@link JavaApiEnricher}, persisted in the enrichment cache as JSON. Raw instances (from the
 * model or the cache) become trustworthy only after
 * {@code NormalizationUtils.canonicalEnrichment}.
 */
public record Enrichment(
        @Nullable @JsonProperty(required = true, value = "description") String description,
        @Nullable @JsonProperty(required = true, value = "example") String example) {
}
