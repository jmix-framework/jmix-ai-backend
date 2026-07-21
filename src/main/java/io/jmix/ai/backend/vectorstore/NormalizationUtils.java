package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.vectorstore.javaapi.Enrichment;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Semantic gates for LLM-generated content that ends up inside formatted snippet cards
 * ({@link Snippet#format()}). Every producer funnels both origins of its data — a live model
 * response and a cached payload — through the same gate, so content the live path would reject or
 * clean up can never resurface unchanged from the cache.
 * <p>
 * The shared cleanup rules: whitespace runs are collapsed because card header lines
 * ({@code TITLE:}/{@code DESCRIPTION:}) are single-line by format, so an embedded newline would
 * corrupt the card layout and its round-trip parsing; markdown fences (whole {@code ```} lines and
 * stray inline backtick runs) are stripped from code because it is embedded into the card's own
 * code fence, where a surviving {@code ```} would terminate the fence early and truncate the card.
 */
public final class NormalizationUtils {

    private NormalizationUtils() {
    }

    /**
     * The canonical form of a Java API card enrichment, or null when there is no usable
     * description (treated as absent enrichment: generation failure or cache miss).
     */
    @Nullable
    public static Enrichment canonicalEnrichment(@Nullable Enrichment enrichment) {
        if (enrichment == null || StringUtils.isBlank(enrichment.description())) {
            return null;
        }
        String example = stripMarkdownFences(enrichment.example());
        return new Enrichment(
                collapseWhitespace(enrichment.description()),
                example == null ? "" : example);
    }

    /**
     * The canonical form of a snippet list: entries without a title or description are dropped,
     * the surviving values are cleaned up, blank language/code become null. Null when nothing
     * usable remains, so an empty result is a failure or cache miss, never a valid value.
     */
    @Nullable
    public static List<Snippet> canonicalSnippets(@Nullable List<Snippet> snippets) {
        if (snippets == null) {
            return null;
        }
        List<Snippet> normalized = snippets.stream()
                .filter(snippet -> !StringUtils.isBlank(snippet.title())
                        && !StringUtils.isBlank(snippet.description()))
                .map(snippet -> new Snippet(
                        collapseWhitespace(snippet.title()),
                        collapseWhitespace(snippet.description()),
                        StringUtils.defaultIfBlank(snippet.language(), null),
                        StringUtils.defaultIfBlank(stripMarkdownFences(snippet.code()), null),
                        snippet.absoluteUrl()))
                .toList();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    @Nullable
    private static String stripMarkdownFences(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(?im)^\\s*```\\w*\\s*$", "")
                .replace("```", "")
                .trim();
    }
}
