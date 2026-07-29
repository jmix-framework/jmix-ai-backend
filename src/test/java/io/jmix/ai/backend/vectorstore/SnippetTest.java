package io.jmix.ai.backend.vectorstore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnippetTest {

    @Test
    void formatAndParseRoundTripWithCode() {
        Snippet snippet = new Snippet(
                "Interface DataManager (io.jmix.core)",
                "Authorization-aware data access facade.",
                "java",
                "public interface DataManager\n\n// Methods\nUnconstrainedDataManager unconstrained()",
                "https://docs.jmix.io/api/2.8/io/jmix/core/DataManager.html");

        assertThat(Snippet.parse(snippet.format())).isEqualTo(snippet);
    }

    @Test
    void formatAndParseRoundTripWithoutCode() {
        Snippet snippet = new Snippet("Title", "Description.", null, null, "https://example.com");

        assertThat(Snippet.parse(snippet.format())).isEqualTo(snippet);
    }

    @Test
    void parseSurvivesCodeContainingFenceLikeLines() {
        Snippet snippet = new Snippet("T", "D", "java", "String s = \"```\";\n// tail", "src");

        assertThat(Snippet.parse(snippet.format())).isEqualTo(snippet);
    }

    @Test
    void parseRejectsArbitraryText() {
        assertThatThrownBy(() -> Snippet.parse("just some text"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
