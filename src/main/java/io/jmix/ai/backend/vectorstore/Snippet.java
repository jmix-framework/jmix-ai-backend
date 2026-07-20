package io.jmix.ai.backend.vectorstore;

import org.springframework.lang.Nullable;

/**
 * A small self-contained piece of knowledge: title, description, optional code and the absolute
 * URL of the page it came from. This is the target format for ingested content (similar to
 * context7 snippets), rendered to plain text by {@link #format()} before embedding.
 * <p>
 * {@code absoluteUrl} is the full page link for citation (e.g. {@code https://docs.jmix.io/...});
 * it is NOT the corpus source identifier (the relative path kept in the {@code source} metadata).
 * <p>
 * Title, description and URL must be single-line: {@link #parse(String)} relies on it
 * to restore a snippet from its formatted text.
 */
public record Snippet(
        String title,
        String description,
        @Nullable String language,
        @Nullable String code,
        String absoluteUrl) {

    /** Starts the first line of a formatted snippet; retrieval recognizes snippet titles by it. */
    public static final String TITLE_PREFIX = "TITLE: ";
    private static final String DESCRIPTION_PREFIX = "DESCRIPTION: ";
    private static final String URL_PREFIX = "URL: ";
    private static final String LANGUAGE_PREFIX = "LANGUAGE: ";
    /** Opens the code section of a formatted snippet, followed by the language and a line break. */
    public static final String CODE_FENCE_START = "\nCODE:\n```";
    /** Terminates the code section; a formatted snippet with code always ends with it. */
    public static final String CODE_FENCE_END = "\n```";

    /**
     * Renders the snippet as the plain text that is embedded and stored in the vector store:
     * <pre>
     * TITLE: Standard delete action of DataGrid
     * DESCRIPTION: The list_remove action removes selected rows and asks for confirmation...
     * URL: https://docs.jmix.io/jmix/flowui/actions/list-actions.html
     * LANGUAGE: java
     * CODE:
     * ```java
     * removeAction.setConfirmation(true);
     * ```
     * </pre>
     * The three header lines are always present and single-line; LANGUAGE and the fenced CODE
     * block appear only when the snippet has code, and such text always ends with the closing
     * fence. This is exactly the shape {@link #parse(String)} restores a snippet from.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(TITLE_PREFIX).append(title).append('\n');
        sb.append(DESCRIPTION_PREFIX).append(description).append('\n');
        sb.append(URL_PREFIX).append(absoluteUrl);
        if (code != null && !code.isBlank()) {
            String lang = language == null ? "" : language;
            if (!lang.isEmpty()) {
                sb.append('\n').append(LANGUAGE_PREFIX).append(lang);
            }
            sb.append(CODE_FENCE_START).append(lang).append('\n')
                    .append(code).append(CODE_FENCE_END);
        }
        return sb.toString();
    }

    /**
     * Restores a snippet from text produced by {@link #format()}.
     *
     * @throws IllegalArgumentException if the text is not a formatted snippet
     */
    public static Snippet parse(String formatted) {
        int codeIdx = formatted.indexOf(CODE_FENCE_START);
        String head = codeIdx < 0 ? formatted : formatted.substring(0, codeIdx);

        String title = null;
        String description = null;
        String absoluteUrl = null;
        String language = null;
        for (String line : head.split("\n")) {
            if (line.startsWith(TITLE_PREFIX)) {
                title = line.substring(TITLE_PREFIX.length());
            } else if (line.startsWith(DESCRIPTION_PREFIX)) {
                description = line.substring(DESCRIPTION_PREFIX.length());
            } else if (line.startsWith(URL_PREFIX)) {
                absoluteUrl = line.substring(URL_PREFIX.length());
            } else if (line.startsWith(LANGUAGE_PREFIX)) {
                language = line.substring(LANGUAGE_PREFIX.length());
            }
        }
        if (title == null || description == null || absoluteUrl == null) {
            throw new IllegalArgumentException("Not a formatted snippet: " + head);
        }

        String code = null;
        if (codeIdx >= 0) {
            if (!formatted.endsWith(CODE_FENCE_END)) {
                throw new IllegalArgumentException("Formatted snippet has unterminated code section");
            }
            int bodyStart = formatted.indexOf('\n', codeIdx + CODE_FENCE_START.length()) + 1;
            code = formatted.substring(bodyStart, formatted.length() - CODE_FENCE_END.length());
        }

        return new Snippet(title, description, language, code, absoluteUrl);
    }
}
