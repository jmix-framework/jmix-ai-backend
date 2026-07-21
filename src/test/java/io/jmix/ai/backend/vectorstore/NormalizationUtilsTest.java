package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.vectorstore.javaapi.Enrichment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationUtilsTest {

    @Test
    void canonicalEnrichment_RejectsMissingOrDescriptionlessEnrichment() {
        assertThat(NormalizationUtils.canonicalEnrichment(null)).isNull();
        assertThat(NormalizationUtils.canonicalEnrichment(new Enrichment(null, "example"))).isNull();
        assertThat(NormalizationUtils.canonicalEnrichment(new Enrichment(" \n ", "example"))).isNull();
    }

    @Test
    void canonicalEnrichment_CollapsesDescriptionWhitespace() {
        Enrichment canonical = NormalizationUtils.canonicalEnrichment(
                new Enrichment("  Data access\n facade. ", ""));

        assertThat(canonical).isNotNull();
        assertThat(canonical.description()).isEqualTo("Data access facade.");
    }

    @Test
    void canonicalEnrichment_StripsFenceLinesAndInlineBackticksFromExample() {
        Enrichment canonical = NormalizationUtils.canonicalEnrichment(
                new Enrichment("Description.", "```java\nDataManager dm; // ```\n```"));

        assertThat(canonical).isNotNull();
        assertThat(canonical.example()).isEqualTo("DataManager dm; //");
    }

    @Test
    void canonicalEnrichment_TreatsMissingExampleAsEmpty() {
        Enrichment canonical = NormalizationUtils.canonicalEnrichment(
                new Enrichment("Description.", null));

        assertThat(canonical).isNotNull();
        assertThat(canonical.example()).isEmpty();
    }

    @Test
    void canonicalSnippets_RejectsMissingEmptyOrAllInvalidLists() {
        assertThat(NormalizationUtils.canonicalSnippets(null)).isNull();
        assertThat(NormalizationUtils.canonicalSnippets(List.of())).isNull();
        assertThat(NormalizationUtils.canonicalSnippets(List.of(
                snippet(" ", "No title."),
                snippet("No description", " ")))).isNull();
    }

    @Test
    void canonicalSnippets_DropsInvalidEntriesAndKeepsValidOnes() {
        List<Snippet> canonical = NormalizationUtils.canonicalSnippets(List.of(
                snippet(" ", "Blank title is dropped."),
                snippet("Button", "Valid snippet.")));

        assertThat(canonical).isNotNull();
        assertThat(canonical).singleElement()
                .extracting(Snippet::title)
                .isEqualTo("Button");
    }

    @Test
    void canonicalSnippets_CollapsesWhitespaceInTitleAndDescription() {
        List<Snippet> canonical = NormalizationUtils.canonicalSnippets(List.of(
                new Snippet("Create\na  Button", " Declares  a\nbutton. ", "xml", "<button/>", "url")));

        assertThat(canonical).isNotNull();
        assertThat(canonical.getFirst().title()).isEqualTo("Create a Button");
        assertThat(canonical.getFirst().description()).isEqualTo("Declares a button.");
    }

    @Test
    void canonicalSnippets_StripsFencesFromCodeAndNullsBlankLanguageAndCode() {
        List<Snippet> canonical = NormalizationUtils.canonicalSnippets(List.of(
                new Snippet("Fenced", "Code keeps only the payload.", "", "```xml\n<button/>\n```", "url"),
                new Snippet("Fence only", "Code of nothing but a fence becomes null.", " ", "```", "url")));

        assertThat(canonical).isNotNull();
        assertThat(canonical.getFirst().language()).isNull();
        assertThat(canonical.getFirst().code()).isEqualTo("<button/>");
        assertThat(canonical.getLast().language()).isNull();
        assertThat(canonical.getLast().code()).isNull();
    }

    @Test
    void canonicalSnippets_KeepsNullCodeAndTheAbsoluteUrl() {
        List<Snippet> canonical = NormalizationUtils.canonicalSnippets(List.of(
                new Snippet("Prose", "A snippet without code.", null, null, "https://docs.jmix.io/page")));

        assertThat(canonical).isNotNull();
        assertThat(canonical.getFirst().code()).isNull();
        assertThat(canonical.getFirst().absoluteUrl()).isEqualTo("https://docs.jmix.io/page");
    }

    private static Snippet snippet(String title, String description) {
        return new Snippet(title, description, null, null, "url");
    }
}
