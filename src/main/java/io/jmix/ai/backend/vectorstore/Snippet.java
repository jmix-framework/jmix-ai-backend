package io.jmix.ai.backend.vectorstore;

import org.springframework.lang.Nullable;

/**
 * A small self-contained piece of knowledge: title, description, optional code and a source URL.
 * This is the target format for ingested content (similar to context7 snippets), rendered to
 * plain text by {@link #format()} before embedding.
 */
public record Snippet(
        String title,
        String description,
        @Nullable String language,
        @Nullable String code,
        String source) {

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
            sb.append('\n').append("CODE:\n```").append(lang).append('\n')
                    .append(code).append("\n```");
        }
        return sb.toString();
    }
}
