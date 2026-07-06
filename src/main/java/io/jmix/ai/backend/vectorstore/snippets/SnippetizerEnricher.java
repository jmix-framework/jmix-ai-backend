package io.jmix.ai.backend.vectorstore.snippets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Splits ingested pages (documentation, UI samples) into small self-contained context7-like
 * snippets using an LLM. Generated snippets are cached in {@link EnrichmentCache} keyed by the
 * hash of the source page, so re-ingestion of unchanged pages costs no LLM calls.
 */
@Component
public class SnippetizerEnricher {

    private static final Logger log = LoggerFactory.getLogger(SnippetizerEnricher.class);

    private static final int MAX_INPUT_CHARS = 60_000;

    private static final String SYSTEM_PROMPT = """
            You convert a page of Jmix framework documentation into small self-contained snippets for a code-search index.
            Return a list of snippets. Rules:
            - Each snippet covers ONE specific topic, task or component usage from the page.
            - "title": specific and searchable; mention the component, class or feature name.
            - "description": 2-5 sentences, self-contained and understandable without the page; explicitly mention key terms, class names, XML attributes and use cases so the text is easy to find by search.
            - "code": if the topic has code on the page, copy the most relevant code block VERBATIM from the page. Never invent, modify or merge code. Use "" if there is no code for the topic.
            - "language": java, xml, groovy, kotlin, sql, properties or plaintext. Use "" when "code" is empty.
            - Cover ALL distinct topics of the page; typically 2-8 snippets per page.
            - Write titles and descriptions in English.
            The input may contain HTML markup - ignore navigation, layout and formatting tags.
            """;

    public record SnippetItem(
            @Nullable @JsonProperty(required = true, value = "title") String title,
            @Nullable @JsonProperty(required = true, value = "description") String description,
            @Nullable @JsonProperty(required = true, value = "language") String language,
            @Nullable @JsonProperty(required = true, value = "code") String code) {
    }

    public record SnippetizationResponse(
            @Nullable @JsonProperty(required = true, value = "snippets") List<SnippetItem> snippets) {
    }

    private static final BeanOutputConverter<SnippetizationResponse> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(SnippetizationResponse.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Snippet>> SNIPPET_LIST_TYPE = new TypeReference<>() {
    };

    private final String modelName;
    private final double temperature;
    private final String reasoningEffort;
    private final int parallelism;
    private final OpenAiApi openAiApi;
    private final EnrichmentCacheRepository enrichmentCacheRepository;

    public SnippetizerEnricher(
            @Value("${snippets.enrichment.model}") String modelName,
            @Value("${snippets.enrichment.temperature}") double temperature,
            @Value("${snippets.enrichment.reasoning-effort:}") String reasoningEffort,
            @Value("${snippets.enrichment.parallelism}") int parallelism,
            @Value("${spring.ai.openai.api-key:}") String configuredApiKey,
            EnrichmentCacheRepository enrichmentCacheRepository) {
        this.modelName = modelName;
        this.temperature = temperature;
        this.reasoningEffort = reasoningEffort;
        this.parallelism = Math.max(1, parallelism);
        this.enrichmentCacheRepository = enrichmentCacheRepository;
        String apiKey = StringUtils.defaultIfBlank(configuredApiKey, System.getenv("OPENAI_API_KEY"));
        this.openAiApi = StringUtils.isBlank(apiKey) ? null : OpenAiApi.builder().apiKey(apiKey).build();
    }

    public String getModelKey() {
        return StringUtils.isBlank(reasoningEffort) ? modelName : modelName + ":" + reasoningEffort;
    }

    /**
     * Resolves snippets for each document from the cache or by parallel LLM generation.
     * Returns a map keyed by document id; a missing key means generation failed for that
     * document and the caller should fall back to non-snippetized chunking.
     * Cache lookups and saves stay on the caller thread (Jmix security context).
     *
     * @param contentExtractor produces the LLM input from a document (e.g. HTML-to-text conversion)
     */
    public Map<String, List<Snippet>> resolveAll(String type, List<Document> documents,
                                                 java.util.function.Function<Document, String> contentExtractor) {
        Map<String, List<Snippet>> result = new HashMap<>();
        List<Document> pending = new ArrayList<>();

        for (Document document : documents) {
            String contentHash = (String) document.getMetadata().get("sourceHash");
            Optional<List<Snippet>> cached = enrichmentCacheRepository
                    .find(type, source(document), jmixVersion(document), getModelKey())
                    .filter(entry -> Objects.equals(contentHash, entry.getContentHash()))
                    .map(EnrichmentCache::getDescription)
                    .map(this::fromJson);
            if (cached.isPresent()) {
                result.put(document.getId(), cached.get());
            } else {
                pending.add(document);
            }
        }

        if (pending.isEmpty()) {
            return result;
        }
        log.info("Generating snippets for {} documents (parallelism {})", pending.size(), parallelism);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(parallelism, pending.size()));
        try {
            List<Future<List<Snippet>>> futures = new ArrayList<>(pending.size());
            for (Document document : pending) {
                futures.add(executor.submit(() -> snippetize(document, contentExtractor.apply(document))));
            }
            for (int i = 0; i < pending.size(); i++) {
                Document document = pending.get(i);
                List<Snippet> snippets = getResult(futures.get(i), source(document));
                if (snippets != null && !snippets.isEmpty()) {
                    result.put(document.getId(), snippets);
                    enrichmentCacheRepository.save(type, source(document), jmixVersion(document), getModelKey(),
                            (String) document.getMetadata().get("sourceHash"), toJson(snippets), "");
                }
                if ((i + 1) % 100 == 0) {
                    log.info("Snippetized {}/{} documents", i + 1, pending.size());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return result;
    }

    /**
     * Generates snippets for one page, or null on failure.
     */
    @Nullable
    public List<Snippet> snippetize(Document document, @Nullable String content) {
        String url = Objects.toString(document.getMetadata().get("url"), source(document));
        String topic = Objects.toString(document.getMetadata().get("docPath"), "");
        if (content == null || content.isBlank()) {
            return null;
        }
        if (content.length() > MAX_INPUT_CHARS) {
            content = content.substring(0, MAX_INPUT_CHARS);
        }
        String userMessage = "Page topic path: %s\nPage URL: %s\n\nPage content:\n%s".formatted(topic, url, content);
        try {
            ChatResponse response = buildChatModel().call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userMessage))));
            String text = getContent(response);
            if (StringUtils.isBlank(text)) {
                log.error("Snippetization response was empty for {}", url);
                return null;
            }
            SnippetizationResponse parsed = OUTPUT_CONVERTER.convert(text);
            if (parsed == null || parsed.snippets() == null || parsed.snippets().isEmpty()) {
                log.error("Snippetization returned no snippets for {}", url);
                return null;
            }
            List<Snippet> snippets = parsed.snippets().stream()
                    .filter(item -> !StringUtils.isBlank(item.title()) && !StringUtils.isBlank(item.description()))
                    .map(item -> new Snippet(
                            item.title().trim(),
                            item.description().replaceAll("\\s+", " ").trim(),
                            StringUtils.defaultIfBlank(item.language(), null),
                            // markdown fences inside code would break the snippet's own code fence
                            item.code() == null ? null
                                    : StringUtils.defaultIfBlank(item.code().replace("```", ""), null),
                            url))
                    .toList();
            return snippets.isEmpty() ? null : snippets;
        } catch (Exception e) {
            log.error("Snippetization request failed for {}", url, e);
            return null;
        }
    }

    @Nullable
    private List<Snippet> getResult(Future<List<Snippet>> future, String source) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Snippetization interrupted", e);
        } catch (ExecutionException e) {
            log.error("Snippetization failed for {}", source, e.getCause());
            return null;
        }
    }

    private String source(Document document) {
        return (String) document.getMetadata().get("source");
    }

    private String jmixVersion(Document document) {
        return (String) document.getMetadata().get("jmixVersion");
    }

    String toJson(List<Snippet> snippets) {
        try {
            return OBJECT_MAPPER.writeValueAsString(snippets);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize snippets", e);
        }
    }

    @Nullable
    List<Snippet> fromJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, SNIPPET_LIST_TYPE);
        } catch (Exception e) {
            log.error("Failed to deserialize cached snippets", e);
            return null;
        }
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
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
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
