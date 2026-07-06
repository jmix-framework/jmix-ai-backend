package io.jmix.ai.backend.vectorstore;

import org.springframework.lang.Nullable;

/**
 * A small self-contained piece of knowledge: title, description, optional code and a source URL.
 * This is the target format for ingested content (similar to context7 snippets), rendered to
 * plain text by {@link #format()} before embedding.
 * <p>
 * Title, description and source must be single-line: {@link #parse(String)} relies on it
 * to restore a snippet from its formatted text.
 */
public record Snippet(
        String title,
        String description,
        @Nullable String language,
        @Nullable String code,
        String source) {

    private static final String CODE_FENCE_START = "\nCODE:\n```";
    private static final String CODE_FENCE_END = "\n```";

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("TITLE: ").append(title).append('\n');
        sb.append("DESCRIPTION: ").append(description).append('\n');
        sb.append("SOURCE: ").append(source);
        if (code != null && !code.isBlank()) {
            String lang = language == null ? "" : language;
            if (!lang.isEmpty()) {
                sb.append('\n').append("LANGUAGE: ").append(lang);
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
        String source = null;
        String language = null;
        for (String line : head.split("\n")) {
            if (line.startsWith("TITLE: ")) {
                title = line.substring("TITLE: ".length());
            } else if (line.startsWith("DESCRIPTION: ")) {
                description = line.substring("DESCRIPTION: ".length());
            } else if (line.startsWith("SOURCE: ")) {
                source = line.substring("SOURCE: ".length());
            } else if (line.startsWith("LANGUAGE: ")) {
                language = line.substring("LANGUAGE: ".length());
            }
        }
        if (title == null || description == null || source == null) {
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

        return new Snippet(title, description, language, code, source);
    }
}
