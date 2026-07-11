package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.vectorstore.Snippet;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class JavaApiCardRendererTest {

    private static final String URL = "https://docs.jmix.io/api/2.8/io/jmix/core/DataManager.html";

    private final JavadocPageParser parser = new JavadocPageParser();
    private final JavaApiCardRenderer renderer = new JavaApiCardRenderer();

    @Test
    void testRenderCard() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("DataManager.html"));
        Snippet card = renderer.render(classDoc, URL);

        assertThat(card.title()).isEqualTo("Interface DataManager (io.jmix.core)");
        assertThat(card.source()).isEqualTo(URL);

        String formatted = card.format();
        assertThat(formatted)
                .startsWith("TITLE: Interface DataManager (io.jmix.core)")
                .contains("DESCRIPTION: Same as UnconstrainedDataManager")
                .contains("SOURCE: " + URL)
                .contains("LANGUAGE: java")
                .contains("public interface DataManager extends UnconstrainedDataManager")
                .contains("// Methods")
                .contains("UnconstrainedDataManager unconstrained() // A convenience method")
                .contains("// Methods inherited from interface io.jmix.core.UnconstrainedDataManager");
    }

    @Test
    void testSplitCardFits() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("DataManager.html"));
        String formatted = renderer.render(classDoc, URL).format();

        assertThat(JavaApiCardRenderer.splitCard(formatted, 30_000)).containsExactly(formatted);
    }

    @Test
    void testSplitCardParts() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("UnconstrainedDataManager.html"));
        String formatted = renderer.render(classDoc, URL).format();
        int maxSize = 2000;

        List<String> parts = JavaApiCardRenderer.splitCard(formatted, maxSize);

        assertThat(parts.size()).isGreaterThan(1);
        String header = formatted.substring(0, formatted.indexOf("```java\n") + "```java\n".length());
        for (String part : parts) {
            assertThat(part.length()).isLessThanOrEqualTo(maxSize);
            assertThat(part).startsWith(header).endsWith("\n```");
        }
        // no content lost: concatenated bodies equal the original body
        String originalBody = formatted.substring(header.length(), formatted.length() - "\n```".length());
        String joinedBody = parts.stream()
                .map(p -> p.substring(header.length(), p.length() - "\n```".length()))
                .collect(Collectors.joining());
        assertThat(joinedBody).isEqualTo(originalBody);
    }

    @Test
    void testSplitCardDoesNotTruncateLongLine() {
        String code = "x".repeat(10_000);
        String formatted = new Snippet("Title", "Description.", "java", code, URL).format();
        String header = formatted.substring(0, formatted.indexOf("```java\n") + "```java\n".length());

        List<String> parts = JavaApiCardRenderer.splitCard(formatted, 1_000);

        assertThat(parts).hasSizeGreaterThan(1)
                .allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(1_000));
        String reconstructedCode = parts.stream()
                .map(part -> part.substring(header.length(), part.length() - "\n```".length()))
                .collect(Collectors.joining());
        assertThat(reconstructedCode).isEqualTo(code);
    }

    @Test
    void testSplitCardSplitsWholeCardWhenHeaderExceedsLimit() {
        String formatted = new Snippet("Title", "d".repeat(1_000), "java", "int value = 1;", URL).format();

        List<String> parts = JavaApiCardRenderer.splitCard(formatted, 500);

        assertThat(parts).hasSizeGreaterThan(1)
                .allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(500));
        assertThat(String.join("", parts)).isEqualTo(formatted);
    }

    private String loadResource(String name) throws IOException {
        return IOUtils.toString(
                Objects.requireNonNull(getClass().getResourceAsStream(name), "missing resource " + name),
                StandardCharsets.UTF_8);
    }
}
