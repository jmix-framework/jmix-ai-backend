package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.vectorstore.Snippet;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats a parsed Javadoc class page into a compact "API card" snippet:
 * verbatim type signature, member signatures and short descriptions.
 */
public class JavaApiCardFormatter {

    public Snippet format(JavadocClassDoc classDoc, String url) {
        StringBuilder code = new StringBuilder();
        code.append(classDoc.typeSignature());

        for (JavadocClassDoc.Note note : classDoc.notes()) {
            code.append("\n// ").append(note.label()).append(' ').append(note.value());
        }
        appendMembers(code, "Fields", classDoc.fields());
        appendMembers(code, "Constructors", classDoc.constructors());
        appendMembers(code, "Methods", classDoc.methods());
        for (String inherited : classDoc.inheritedMembers()) {
            code.append("\n\n// ").append(inherited);
        }

        String description = classDoc.description().isBlank()
                ? "Java API reference for %s.".formatted(classDoc.fullyQualifiedName())
                : classDoc.description();

        return new Snippet(
                classDoc.title() + " (" + classDoc.packageName() + ")",
                description,
                "java",
                code.toString(),
                url);
    }

    private void appendMembers(StringBuilder sb, String caption, List<JavadocClassDoc.Member> members) {
        if (members.isEmpty()) {
            return;
        }
        sb.append("\n\n// ").append(caption);
        for (JavadocClassDoc.Member member : members) {
            sb.append('\n').append(member.render());
        }
    }

    /**
     * Splits a formatted card without dropping source text, repeating the header
     * (TITLE..CODE fence) in each part when it fits. Every returned part stays within
     * {@code maxSize}.
     */
    public static List<String> splitCard(String cardText, int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        if (cardText.length() <= maxSize) {
            return List.of(cardText);
        }
        int codeIdx = cardText.indexOf(Snippet.CODE_FENCE_START);
        if (codeIdx < 0) {
            return splitText(cardText, maxSize);
        }
        int bodyLineBreak = cardText.indexOf('\n', codeIdx + Snippet.CODE_FENCE_START.length());
        if (bodyLineBreak < 0 || !cardText.endsWith(Snippet.CODE_FENCE_END)) {
            return splitText(cardText, maxSize);
        }
        int bodyStart = bodyLineBreak + 1;
        String header = cardText.substring(0, bodyStart);
        String body = cardText.substring(bodyStart, cardText.length() - Snippet.CODE_FENCE_END.length());

        int budget = maxSize - header.length() - Snippet.CODE_FENCE_END.length();
        if (budget <= 0) {
            return splitText(cardText, maxSize);
        }
        List<String> parts = new ArrayList<>();
        for (String bodyPart : splitText(body, budget)) {
            parts.add(header + bodyPart + Snippet.CODE_FENCE_END);
        }
        return parts;
    }

    private static List<String> splitText(String text, int maxSize) {
        if (text.length() <= maxSize) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(start + maxSize, text.length());
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int lineEnd = text.lastIndexOf('\n', hardEnd - 1);
                if (lineEnd >= start) {
                    end = lineEnd + 1;
                }
            }
            if (end == start) {
                end = hardEnd;
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }
}
