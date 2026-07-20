package io.jmix.ai.backend.vectorstore.javaapi;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Parses standard Javadoc (JDK 11+) class pages into {@link JavadocClassDoc}.
 */
public class JavadocPageParser {

    public JavadocClassDoc parse(String html) {
        return parse(html, href -> true);
    }

    /**
     * @param referenceAllowed decides, by its {@code href}, whether a linked type is kept in the
     *                         "All Known Implementing Classes" note — used to drop blacklisted
     *                         internal ({@code impl/}) classes without losing the other notes.
     */
    JavadocClassDoc parse(String html, Predicate<String> referenceAllowed) {
        Document doc = Jsoup.parse(html);

        // Page header: the package link and the "Interface Foo" / "Class Foo" / "Enum Class Foo" title.
        String packageName = text(doc.selectFirst("div.header div.sub-title a[href$=package-summary.html]"));
        String title = text(doc.selectFirst("div.header h1.title"));

        String typeSignature = "";
        String description = "";
        List<JavadocClassDoc.Note> notes = new ArrayList<>();

        // The class description block: verbatim type signature, the class Javadoc, and the
        // dl.notes definition lists ("All Superinterfaces:", "All Known Implementing Classes:", ...).
        // A page without this section is not a class page; typeSignature stays blank and the caller skips it.
        Element descriptionSection = doc.selectFirst("section.class-description");
        if (descriptionSection != null) {
            typeSignature = text(descriptionSection.selectFirst("div.type-signature"));
            description = text(descriptionSection.selectFirst("div.block"));
            // Each dl.notes pairs dt labels with the following dd value(s); walk children in order,
            // remembering the last dt as the label for the dd(s) that follow it.
            for (Element dl : descriptionSection.select("dl.notes")) {
                String label = null;
                for (Element child : dl.children()) {
                    if (child.tagName().equals("dt")) {
                        label = normalize(child.text());
                    } else if (child.tagName().equals("dd") && label != null) {
                        if (label.equals("All Known Implementing Classes:")) {
                            // Filter by link href so blacklisted impl classes drop out; keep the
                            // note only if at least one allowed implementer remains.
                            String value = child.select("a[href]").stream()
                                    .filter(link -> referenceAllowed.test(link.attr("href")))
                                    .map(link -> normalize(link.text()))
                                    .filter(valuePart -> !valuePart.isBlank())
                                    .collect(Collectors.joining(", "));
                            if (!value.isBlank()) {
                                notes.add(new JavadocClassDoc.Note(label, value));
                            }
                        } else {
                            notes.add(new JavadocClassDoc.Note(label, normalize(child.text())));
                        }
                    }
                }
            }
        }

        // Member summary sections. Constants live in their own section; annotation elements are
        // split into required/optional member-summary sections — both map onto "methods".
        List<JavadocClassDoc.Member> fields = parseSummaryTable(doc, "section.field-summary");
        fields.addAll(parseSummaryTable(doc, "section.constants-summary"));
        List<JavadocClassDoc.Member> constructors = parseSummaryTable(doc, "section.constructor-summary");
        List<JavadocClassDoc.Member> methods = parseSummaryTable(doc, "section.method-summary");
        methods.addAll(parseSummaryTable(doc,
                "section.member-summary[id^=annotation-interface-]"));

        // Inherited members are only listed by name (no signatures) under "... inherited from ...".
        List<String> inheritedMembers = doc.select("div.inherited-list").stream()
                .map(el -> {
                    String heading = text(el.selectFirst("h3"));
                    String members = text(el.selectFirst("code"));
                    return members.isBlank() ? heading : heading + ": " + members;
                })
                .filter(s -> !s.isBlank())
                .toList();

        return new JavadocClassDoc(packageName, title, typeSignature, description, notes,
                fields, constructors, methods, inheritedMembers);
    }

    /**
     * A Javadoc summary "table" is a CSS grid, not a {@code <table>}: its cells are sibling divs
     * in the fixed order col-first (modifier and type), col-second / col-constructor-name
     * (the member signature), col-last (the description). We accumulate the first two and flush a
     * {@link JavadocClassDoc.Member} on col-last, which marks the end of one row. table-header
     * cells are the column captions and are skipped.
     */
    private List<JavadocClassDoc.Member> parseSummaryTable(Document doc, String sectionSelector) {
        List<JavadocClassDoc.Member> members = new ArrayList<>();
        for (Element table : doc.select(sectionSelector + " div.summary-table")) {
            String first = "";
            String second = "";
            for (Element col : table.children()) {
                if (col.hasClass("table-header")) {
                    continue;
                }
                if (col.hasClass("col-first")) {
                    first = text(col);
                } else if (col.hasClass("col-second") || col.hasClass("col-constructor-name")) {
                    second = text(col);
                } else if (col.hasClass("col-last")) {
                    members.add(new JavadocClassDoc.Member(first, second, text(col)));
                    first = "";
                    second = "";
                }
            }
        }
        return members;
    }

    private String text(@Nullable Element element) {
        return element == null ? "" : normalize(element.text());
    }

    private String normalize(String text) {
        return text.replace('\u00A0', ' ').trim();
    }
}
