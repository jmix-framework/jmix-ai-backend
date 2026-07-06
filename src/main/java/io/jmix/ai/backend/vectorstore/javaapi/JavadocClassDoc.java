package io.jmix.ai.backend.vectorstore.javaapi;

import java.util.List;

/**
 * Structured content of a standard Javadoc class page. Signatures and descriptions are extracted
 * verbatim from the page, so downstream rendering or LLM enrichment cannot invent API members.
 */
public record JavadocClassDoc(
        String packageName,
        String title,
        String typeSignature,
        String description,
        List<Note> notes,
        List<Member> fields,
        List<Member> constructors,
        List<Member> methods,
        List<String> inheritedMembers) {

    public record Member(String modifierAndType, String signature, String description) {

        public String render() {
            StringBuilder sb = new StringBuilder();
            if (!modifierAndType.isBlank()) {
                sb.append(modifierAndType);
            }
            if (!signature.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(signature);
            }
            if (!description.isBlank()) {
                sb.append(" // ").append(description);
            }
            return sb.toString();
        }
    }

    public record Note(String label, String value) {
    }

    /**
     * Simple class name derived from the page title, e.g. "Interface DataManager" -> "DataManager".
     */
    public String className() {
        String name = title.substring(title.lastIndexOf(' ') + 1);
        int genericsIdx = name.indexOf('<');
        return genericsIdx > 0 ? name.substring(0, genericsIdx) : name;
    }

    public String fullyQualifiedName() {
        return packageName.isBlank() ? className() : packageName + "." + className();
    }
}
