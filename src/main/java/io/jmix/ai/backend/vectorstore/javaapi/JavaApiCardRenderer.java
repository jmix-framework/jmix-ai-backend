package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.vectorstore.Snippet;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a parsed Javadoc class page into a compact "API card" snippet:
 * verbatim type signature, member signatures and short descriptions.
 */
public class JavaApiCardRenderer {

    public Snippet render(JavadocClassDoc classDoc, String url) {
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
     * Splits a formatted card into parts not exceeding {@code maxSize}, repeating the header
     * (TITLE..CODE fence) in each part. Cards small enough are returned as a single element.
     */
    public static List<String> splitCard(String cardText, int maxSize) {
        if (cardText.length() <= maxSize) {
            return List.of(cardText);
        }
        String fenceStart = "CODE:\n```";
        int codeIdx = cardText.indexOf(fenceStart);
        if (codeIdx < 0) {
            // no code section to split by, hard cut
            return List.of(cardText.substring(0, maxSize));
        }
        int bodyStart = cardText.indexOf('\n', codeIdx + fenceStart.length()) + 1;
        String footer = "\n```";
        String header = cardText.substring(0, bodyStart);
        String body = cardText.substring(bodyStart, cardText.length() - footer.length());

        int budget = maxSize - header.length() - footer.length();
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.length() > budget) {
                line = line.substring(0, budget);
            }
            if (!current.isEmpty() && current.length() + line.length() + 1 > budget) {
                parts.add(header + current + footer);
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            parts.add(header + current + footer);
        }
        return parts;
    }
}
