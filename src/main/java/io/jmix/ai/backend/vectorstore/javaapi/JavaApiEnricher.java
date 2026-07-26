package io.jmix.ai.backend.vectorstore.javaapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.vectorstore.AbstractOpenAiEnricher;
import io.jmix.ai.backend.vectorstore.NormalizationUtils;
import io.jmix.ai.backend.vectorstore.Snippet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Generates an LLM-written description and usage example for a Java API card.
 * Generation is grounded: the model receives only verbatim signatures from the card and is
 * instructed to never use members that are not listed; signatures themselves are not rewritten —
 * {@link #assembleCard} keeps them verbatim and only adds the generated prose.
 */
@Component
public class JavaApiEnricher extends AbstractOpenAiEnricher {

    private static final Logger log = LoggerFactory.getLogger(JavaApiEnricher.class);

    private static final String SYSTEM_PROMPT = """
            You are a Java API documentation writer for the Jmix framework.
            You are given a reference card for one Java type. The card contains signatures extracted verbatim from Javadoc.
            Write:
            1. "description": 2-4 sentences describing the purpose of this type, when a Jmix developer needs it, and how it relates to the types mentioned in the card. Mention key terms and use cases explicitly so the text is easy to find by search.
            2. "example": a short realistic Java snippet (5-15 lines) showing typical usage of this type. A compact fragment is preferred over a full class; imports and class scaffolding are not required.
            STRICT RULES:
            - Use ONLY the classes, methods, fields and constructors listed in the card. Never invent members that are not listed.
            - In the example, call only methods listed in the card. Never guess member methods of OTHER types (e.g. of parameters or returned objects): if they are needed, keep them behind a placeholder comment like /* ... */ instead of inventing calls.
            - ALWAYS write an example when the card lists at least one method, constructor, field or enum constant. Return an empty string for "example" only for marker types with no listed members.
            - Do not mention Javadoc or the card itself.
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Serializes the enrichment for the {@code EnrichmentCache} content. */
    public static String toCacheJson(Enrichment enrichment) {
        try {
            return OBJECT_MAPPER.writeValueAsString(enrichment);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize enrichment", e);
        }
    }

    /**
     * Restores the enrichment from cached content, or null if unreadable or semantically invalid
     * (both a cache miss). Cached data passes the same gate as a live response, so a stale or
     * hand-edited entry can never mark a card as enriched without a usable description.
     */
    @Nullable
    public static Enrichment fromCacheJson(String json) {
        try {
            return NormalizationUtils.canonicalEnrichment(OBJECT_MAPPER.readValue(json, Enrichment.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached enrichment", e);
            return null;
        }
    }

    private static final BeanOutputConverter<Enrichment> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(Enrichment.class);

    // set to the date of the change on SYSTEM_PROMPT changes: invalidates the enrichment cache
    // (paid LLM regeneration) and rebuilds existing chunks; never reuse a value
    private static final String PROMPT_VERSION = "prompt-version-2026-07-26";

    public JavaApiEnricher(
            @Value("${javaapi.enrichment.model}") String modelName,
            @Value("${javaapi.enrichment.reasoning-effort:}") String reasoningEffort,
            @Value("${spring.ai.openai.api-key:}") String configuredApiKey,
            @Value("${enrichment.openai.connect-timeout}") Duration connectTimeout,
            @Value("${enrichment.openai.read-timeout}") Duration readTimeout) {
        super(modelName, reasoningEffort, configuredApiKey, false, connectTimeout, readTimeout);
    }

    @Override
    public String getModelKey() {
        return super.getModelKey() + ":" + PROMPT_VERSION;
    }

    @Override
    protected BeanOutputConverter<?> outputConverter() {
        return OUTPUT_CONVERTER;
    }

    /**
     * Returns generated enrichment for the card, or null if generation failed
     * (the caller falls back to the deterministic card).
     */
    @Nullable
    public Enrichment enrich(String cardText) {
        try {
            ChatResponse response = chatModel().call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(cardText))));
            String content = getContent(response);
            if (StringUtils.isBlank(content)) {
                log.error("Enrichment response was empty");
                return null;
            }
            // the same gate cached payloads pass in fromCacheJson: live and cache cannot diverge
            Enrichment enrichment = NormalizationUtils.canonicalEnrichment(OUTPUT_CONVERTER.convert(content));
            if (enrichment == null) {
                log.error("Enrichment response has no description: {}", content);
                return null;
            }
            return enrichment;
        } catch (Exception e) {
            log.error("Enrichment request failed", e);
            return null;
        }
    }

    /**
     * Combines the deterministic card with the generated enrichment: description is replaced with
     * the generated one, the example is appended to the code section, signatures stay verbatim.
     */
    public static String assembleCard(Snippet card, @Nullable Enrichment enrichment) {
        // re-gate defensively: the enrichment may come from outside the canonical gate
        Enrichment canonical = NormalizationUtils.canonicalEnrichment(enrichment);
        if (canonical == null) {
            return card.format();
        }
        String code = card.code() == null ? "" : card.code();
        if (!StringUtils.isBlank(canonical.example())) {
            code = code + "\n\n// Usage example:\n" + canonical.example();
        }
        return new Snippet(card.title(), canonical.description(), card.language(), code, card.absoluteUrl())
                .format();
    }
}
