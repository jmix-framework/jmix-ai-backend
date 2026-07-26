package io.jmix.ai.backend.vectorstore.snippets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.vectorstore.AbstractOpenAiEnricher;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
import io.jmix.ai.backend.vectorstore.NormalizationUtils;
import io.jmix.ai.backend.vectorstore.Snippet;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Splits ingested pages (documentation, UI samples) into small self-contained context7-like
 * snippets using an LLM. Generated snippets are cached in {@link EnrichmentCache} keyed by the
 * hash of the source page, so re-ingestion of unchanged pages costs no LLM calls.
 */
@Component
public class SnippetizerEnricher extends AbstractOpenAiEnricher {

    private static final Logger log = LoggerFactory.getLogger(SnippetizerEnricher.class);

    static final int MAX_INPUT_CHARS = 60_000;
    static final int MAX_COVERAGE_CHARS = 8_000;

    // set to the date of the change when the snippetization prompt changes: invalidates the
    // snippet cache (paid LLM regeneration); never reuse a value
    private static final String PROMPT_VERSION = "prompt-version-2026-07-26";
    // set to the date of the change whenever validation, the stored snippet format or the
    // DocsHtmlConverter output changes; rebuilds the chunks while reusing cached LLM output
    // (unlike a PROMPT_VERSION change). Dated (not "vN") so it cannot be confused with Jmix
    // versions, and a value must never be reused — a reused value would make chunks built by
    // the old code look current.
    private static final String SNIPPET_FORMAT_VERSION = "snippet-format-version-2026-07-26";

    private static final String SYSTEM_PROMPT = """
            You convert a page of Jmix framework documentation into small self-contained snippets for a code-search index.
            Return a list of snippets. Rules:
            - Each snippet covers ONE specific topic, task or component usage from the page.
            - "title": specific and searchable; mention the component, class or feature name.
            - "description": 2-5 sentences, self-contained and understandable without the page; explicitly mention key terms, class names, XML attributes and use cases so the text is easy to find by search.
            - "code": if the topic has code on the page, copy the most relevant code block VERBATIM from the page. Never invent, modify or merge code. Use "" if there is no code for the topic.
            - Keep declarative code COMPLETE: when the example uses Jmix annotations or declarative wiring
              (e.g. @Install, @Subscribe, @Supply, @ViewComponent, @Autowired) or an XML element with attributes,
              include the whole element - the annotation(s) TOGETHER WITH the method signature it decorates,
              or the full XML tag with its attributes - never the bare method body without its annotation.
              If a usage needs both a declaration and its handler, keep them in the SAME snippet so it is
              self-contained and copy-pasteable.
            - When the code uses one variant of an API that has alternatives (an overload, a builder
              entry point, a combination of attributes), name that exact variant in the description and
              state the practical effect that makes it the right choice - especially what it wires up
              automatically (e.g. an overload accepting an existing UI component that links it
              automatically, versus a generic overload that needs manual glue code). Describe the
              user-visible outcome as well as the mechanism, so goal-phrased searches find the snippet.
            - "language": java, xml, groovy, kotlin, sql, properties or plaintext. Use "" when "code" is empty.
            - Cover ALL distinct topics of the page; typically 2-8 snippets per page.
            - For a numbered page part, cover all distinct topics present in that part.
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

    private final int parallelism;
    private final EnrichmentCacheRepository enrichmentCacheRepository;
    private final ExecutorService executor;

    public SnippetizerEnricher(
            @Value("${snippets.enrichment.model}") String modelName,
            @Value("${snippets.enrichment.reasoning-effort:}") String reasoningEffort,
            @Value("${snippets.enrichment.parallelism}") int parallelism,
            @Value("${spring.ai.openai.api-key:}") String configuredApiKey,
            @Value("${enrichment.openai.connect-timeout}") Duration connectTimeout,
            @Value("${enrichment.openai.read-timeout}") Duration readTimeout,
            EnrichmentCacheRepository enrichmentCacheRepository) {
        super(modelName, reasoningEffort, configuredApiKey, false, connectTimeout, readTimeout);
        this.parallelism = Math.max(1, parallelism);
        this.enrichmentCacheRepository = enrichmentCacheRepository;
        this.executor = Executors.newFixedThreadPool(this.parallelism);
    }

    @Override
    protected BeanOutputConverter<?> outputConverter() {
        return OUTPUT_CONVERTER;
    }

    @Override
    public String getModelKey() {
        return super.getModelKey() + ":" + PROMPT_VERSION;
    }

    public String getGenerationKey() {
        return getModelKey() + ":" + SNIPPET_FORMAT_VERSION;
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
        Map<String, String> contentByDocumentId = new HashMap<>();
        List<Document> pending = new ArrayList<>();

        for (Document document : documents) {
            String content = contentExtractor.apply(document);
            contentByDocumentId.put(document.getId(), content);
            String contentHash = (String) document.getMetadata().get("sourceHash");
            Optional<List<Snippet>> cached = enrichmentCacheRepository
                    .find(type, source(document), jmixVersion(document), getModelKey())
                    .filter(entry -> Objects.equals(contentHash, entry.getContentHash()))
                    .map(EnrichmentCache::getContent)
                    .map(json -> fromJson(json, absoluteUrl(document)))
                    .filter(snippets -> containsOnlyVerbatimCode(snippets, content));
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
        CompletionService<List<Snippet>> completed = new ExecutorCompletionService<>(executor);
        Map<Future<List<Snippet>>, Document> inFlight = new HashMap<>();
        int next = 0;
        int completedCount = 0;
        try {
            while (next < pending.size() && inFlight.size() < parallelism) {
                Document document = pending.get(next++);
                inFlight.put(completed.submit(
                        () -> snippetize(document, contentByDocumentId.get(document.getId()))), document);
            }

            while (!inFlight.isEmpty()) {
                Future<List<Snippet>> future = completed.take();
                Document document = inFlight.remove(future);
                List<Snippet> snippets = getResult(future, source(document));
                if (snippets != null && !snippets.isEmpty()) {
                    result.put(document.getId(), snippets);
                    enrichmentCacheRepository.save(type, source(document), jmixVersion(document), getModelKey(),
                            (String) document.getMetadata().get("sourceHash"), toJson(snippets));
                }
                completedCount++;
                if (completedCount % 100 == 0) {
                    log.info("Snippetized {}/{} documents", completedCount, pending.size());
                }
                if (next < pending.size()) {
                    Document nextDocument = pending.get(next++);
                    inFlight.put(completed.submit(
                            () -> snippetize(nextDocument, contentByDocumentId.get(nextDocument.getId()))),
                            nextDocument);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Snippetization interrupted", e);
        } finally {
            inFlight.keySet().forEach(future -> future.cancel(true));
        }
        return result;
    }

    /**
     * Generates snippets for one page, or null on failure.
     */
    @Nullable
    public List<Snippet> snippetize(Document document, @Nullable String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        List<String> parts = splitContent(content, MAX_INPUT_CHARS);
        List<Snippet> result = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            List<Snippet> partSnippets = snippetizePart(document, parts.get(i), i + 1, parts.size());
            if (partSnippets == null) {
                return null;
            }
            result.addAll(partSnippets);
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

    @Nullable
    private List<Snippet> snippetizePart(Document document, String content, int partNumber, int partCount) {
        String url = absoluteUrl(document);
        String topic = Objects.toString(document.getMetadata().get("docPath"), "");
        String partInfo = partCount == 1 ? "" : "\nPage content part: %d/%d".formatted(partNumber, partCount);
        String userMessage = "Page topic path: %s\nPage URL: %s%s\n\nPage content:\n%s"
                .formatted(topic, url, partInfo, content);
        try {
            ChatResponse response = chatModel().call(new Prompt(List.of(
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
            // the same gate cached payloads pass in fromJson: live and cache cannot diverge
            List<Snippet> snippets = NormalizationUtils.canonicalSnippets(parsed.snippets().stream()
                    .map(item -> new Snippet(item.title(), item.description(), item.language(), item.code(), url))
                    .toList());
            if (snippets == null) {
                return null;
            }
            if (!containsOnlyVerbatimCode(snippets, content)) {
                log.error("Snippetization invented or modified code for {}", url);
                return null;
            }
            return snippets;
        } catch (Exception e) {
            log.error("Snippetization request failed for {}", url, e);
            return null;
        }
    }

    static List<String> splitContent(String content, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (content.length() <= maxChars) {
            return List.of(content);
        }

        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int hardEnd = Math.min(start + maxChars, content.length());
            int end = hardEnd;
            if (hardEnd < content.length()) {
                int preferredStart = start + maxChars / 2;
                int paragraphEnd = content.lastIndexOf("\n\n", hardEnd - 2);
                if (paragraphEnd >= preferredStart) {
                    end = paragraphEnd + 2;
                } else {
                    int lineEnd = content.lastIndexOf('\n', hardEnd - 1);
                    if (lineEnd >= preferredStart) {
                        end = lineEnd + 1;
                    }
                }
            }
            parts.add(content.substring(start, end));
            start = end;
        }
        return parts;
    }

    static boolean containsOnlyVerbatimCode(@Nullable List<Snippet> snippets, @Nullable String sourceContent) {
        if (snippets == null || snippets.isEmpty() || sourceContent == null) {
            return false;
        }
        return snippets.stream()
                .map(Snippet::code)
                .filter(StringUtils::isNotBlank)
                .allMatch(sourceContent::contains);
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
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

    /** The current citation link of the page (its url metadata), falling back to the source path. */
    private String absoluteUrl(Document document) {
        return Objects.toString(document.getMetadata().get("url"), source(document));
    }

    private JmixVersion jmixVersion(Document document) {
        return JmixVersion.fromId((String) document.getMetadata().get("jmixVersion"));
    }

    String toJson(List<Snippet> snippets) {
        try {
            return OBJECT_MAPPER.writeValueAsString(snippets);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize snippets", e);
        }
    }

    /**
     * Restores snippets from cached content bound to the given current URL of the page, or null
     * if unreadable or semantically invalid (both a cache miss). Cached data passes the same gate
     * as a live response, so a stale or hand-edited entry can never resurface snippets the live
     * path would have rejected. The URL is deterministic page metadata, not generated payload:
     * it may change while the content stays the same (e.g. a docs site move), so the cached
     * value is always replaced with the current one.
     */
    @Nullable
    List<Snippet> fromJson(String json, String absoluteUrl) {
        try {
            return NormalizationUtils.canonicalSnippets(OBJECT_MAPPER.readValue(json, SNIPPET_LIST_TYPE).stream()
                    .map(snippet -> new Snippet(
                            snippet.title(), snippet.description(), snippet.language(), snippet.code(), absoluteUrl))
                    .toList());
        } catch (Exception e) {
            log.error("Failed to deserialize cached snippets", e);
            return null;
        }
    }
}
