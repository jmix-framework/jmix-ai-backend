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

    JavadocClassDoc parse(String html, Predicate<String> referenceAllowed) {
        Document doc = Jsoup.parse(html);

        String packageName = text(doc.selectFirst("div.header div.sub-title a[href$=package-summary.html]"));
        String title = text(doc.selectFirst("div.header h1.title"));

        String typeSignature = "";
        String description = "";
        List<JavadocClassDoc.Note> notes = new ArrayList<>();

        Element descriptionSection = doc.selectFirst("section.class-description");
        if (descriptionSection != null) {
            typeSignature = text(descriptionSection.selectFirst("div.type-signature"));
            description = text(descriptionSection.selectFirst("div.block"));
            for (Element dl : descriptionSection.select("dl.notes")) {
                String label = null;
                for (Element child : dl.children()) {
                    if (child.tagName().equals("dt")) {
                        label = normalize(child.text());
                    } else if (child.tagName().equals("dd") && label != null) {
                        if (label.equals("All Known Implementing Classes:")) {
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

        List<JavadocClassDoc.Member> fields = parseSummaryTable(doc, "section.field-summary");
        fields.addAll(parseSummaryTable(doc, "section.constants-summary"));
        List<JavadocClassDoc.Member> constructors = parseSummaryTable(doc, "section.constructor-summary");
        List<JavadocClassDoc.Member> methods = parseSummaryTable(doc, "section.method-summary");
        methods.addAll(parseSummaryTable(doc,
                "section.member-summary[id^=annotation-interface-]"));

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
