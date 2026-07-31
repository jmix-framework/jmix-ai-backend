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
 * code fence, where a surviving {@code ```} would terminate the fence early and truncate the card;
 * NUL characters are stripped because a model occasionally emits a NUL (U+0000) JSON escape
 * (typically meaning the Java default char literal) and PostgreSQL TEXT rejects the NUL byte —
 * the insert into the vector store would fail for the whole batch.
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
        if (enrichment == null) {
            return null;
        }
        String description = stripNul(enrichment.description());
        if (StringUtils.isBlank(description)) {
            return null;
        }
        String example = stripMarkdownFences(stripNul(enrichment.example()));
        return new Enrichment(
                collapseWhitespace(description),
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
                .filter(snippet -> !StringUtils.isBlank(stripNul(snippet.title()))
                        && !StringUtils.isBlank(stripNul(snippet.description())))
                .map(snippet -> new Snippet(
                        collapseWhitespace(stripNul(snippet.title())),
                        collapseWhitespace(stripNul(snippet.description())),
                        StringUtils.defaultIfBlank(stripNul(snippet.language()), null),
                        StringUtils.defaultIfBlank(stripMarkdownFences(stripNul(snippet.code())), null),
                        snippet.absoluteUrl()))
                .toList();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Removes NUL (U+0000) characters — the one code point PostgreSQL TEXT cannot store. Apply to
     * any model-produced text bound for the database: the snippet/enrichment gates above use it,
     * and so do the check-run and chat-log writers, whose LLM answers and judge rationales hit the
     * same PostgreSQL limitation.
     */
    @Nullable
    public static String stripNul(@Nullable String text) {
        if (text == null || text.indexOf('\u0000') < 0) {
            return text;
        }
        return text.replace("\u0000", "");
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
