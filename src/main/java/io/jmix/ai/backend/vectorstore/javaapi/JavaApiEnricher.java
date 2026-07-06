package io.jmix.ai.backend.vectorstore.javaapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.jmix.ai.backend.vectorstore.Snippet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Generates an LLM-written description and usage example for a Java API card.
 * Generation is grounded: the model receives only verbatim signatures from the card and is
 * instructed to never use members that are not listed; signatures themselves are not rewritten —
 * {@link #assembleCard} keeps them verbatim and only adds the generated prose.
 */
@Component
public class JavaApiEnricher {

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

    public record Enrichment(
            @Nullable @JsonProperty(required = true, value = "description") String description,
            @Nullable @JsonProperty(required = true, value = "example") String example) {
    }

    private static final BeanOutputConverter<Enrichment> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(Enrichment.class);

    private final boolean enabled;
    private final String modelName;
    private final double temperature;
    private final String reasoningEffort;
    private final OpenAiApi openAiApi;

    public JavaApiEnricher(
            @Value("${javaapi.enrichment.enabled}") boolean enabled,
            @Value("${javaapi.enrichment.model}") String modelName,
            @Value("${javaapi.enrichment.temperature}") double temperature,
            @Value("${javaapi.enrichment.reasoning-effort:}") String reasoningEffort,
            @Value("${spring.ai.openai.api-key:}") String configuredApiKey) {
        this.enabled = enabled;
        this.modelName = modelName;
        this.temperature = temperature;
        this.reasoningEffort = reasoningEffort;
        String apiKey = StringUtils.defaultIfBlank(configuredApiKey, System.getenv("OPENAI_API_KEY"));
        if (enabled && StringUtils.isBlank(apiKey)) {
            throw new IllegalStateException("OPENAI API key is not set (spring.ai.openai.api-key or OPENAI_API_KEY)");
        }
        this.openAiApi = StringUtils.isBlank(apiKey) ? null : OpenAiApi.builder().apiKey(apiKey).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Cache key for generated enrichments: model plus reasoning effort, since both affect the output.
     */
    public String getModelKey() {
        return StringUtils.isBlank(reasoningEffort) ? modelName : modelName + ":" + reasoningEffort;
    }

    /**
     * Returns generated enrichment for the card, or null if generation failed
     * (the caller falls back to the deterministic card).
     */
    @Nullable
    public Enrichment enrich(String cardText) {
        try {
            ChatResponse response = buildChatModel().call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(cardText))));
            String content = getContent(response);
            if (StringUtils.isBlank(content)) {
                log.error("Enrichment response was empty");
                return null;
            }
            Enrichment enrichment = OUTPUT_CONVERTER.convert(content);
            if (enrichment == null || StringUtils.isBlank(enrichment.description())) {
                log.error("Enrichment response has no description: {}", content);
                return null;
            }
            return new Enrichment(
                    enrichment.description().trim(),
                    enrichment.example() == null ? "" : enrichment.example().trim());
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
        if (enrichment == null || StringUtils.isBlank(enrichment.description())) {
            return card.format();
        }
        String code = card.code() == null ? "" : card.code();
        if (!StringUtils.isBlank(enrichment.example())) {
            code = code + "\n\n// Usage example:\n" + enrichment.example();
        }
        String description = enrichment.description().replaceAll("\\s+", " ").trim();
        return new Snippet(card.title(), description, card.language(), code, card.source()).format();
    }

    protected ChatModel buildChatModel() {
        if (openAiApi == null) {
            throw new IllegalStateException("OPENAI API key is not set (spring.ai.openai.api-key or OPENAI_API_KEY)");
        }
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, OUTPUT_CONVERTER.getJsonSchema()));
        if (!StringUtils.isBlank(reasoningEffort)) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }
        OpenAiChatOptions options = optionsBuilder.build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    @Nullable
    private static String getContent(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }
}
