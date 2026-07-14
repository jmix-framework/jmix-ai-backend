package io.jmix.ai.backend.vectorstore.snippets;

import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnippetsIngestersTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private TimeSource timeSource;
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    @Mock
    private SnippetizerEnricher snippetizer;

    private DocsSnippetsIngester docsIngester;
    private UiSamplesSnippetsIngester uiSamplesIngester;

    @BeforeEach
    void setUp() {
        docsIngester = new DocsSnippetsIngester("https://docs/v2", "https://docs/v3", "intro.html", 0,
                vectorStore, timeSource, vectorStoreRepository, snippetizer);
        uiSamplesIngester = new UiSamplesSnippetsIngester("https://us/v2", "https://us/v3", "doc", "sample", 0,
                vectorStore, timeSource, vectorStoreRepository, snippetizer);
    }

    private Document docsPage() {
        String paragraph = "The button component allows users to trigger actions in the application. ".repeat(10);
        return new Document("doc-1", "<article class=\"doc\"><p>" + paragraph + "</p></article>", Map.of(
                "type", "docs-snippets",
                "source", "flow-ui/button.html",
                "sourceHash", "hash1",
                "jmixVersion", "v2",
                "url", "https://docs/v2/flow-ui/button.html",
                "docPath", "Flow UI > Button"));
    }

    private Document samplePage() {
        return new Document("sample-1", "Path: Samples > Button\n\nSample content", Map.of(
                "type", "uisamples-snippets",
                "source", "button-sample",
                "sourceHash", "hash2",
                "jmixVersion", "v2",
                "url", "https://us/v2/sample/button-sample"));
    }

    @Test
    void docsSnippets_ProducesSnippetDocumentsWithPathPrefix() {
        when(snippetizer.getGenerationKey()).thenReturn("model:p3:verbatim-code-coverage-v1");
        Document page = docsPage();
        List<Snippet> snippets = List.of(
                new Snippet("Create a Button", "How to declare a button.", "xml", "<button/>", "https://docs/v2/flow-ui/button.html"),
                new Snippet("Button click handler", "Subscribe to click.", "java", "@Subscribe void onClick() {}", "https://docs/v2/flow-ui/button.html"));
        when(snippetizer.resolveAll(eq("docs-snippets"), anyList(), any()))
                .thenReturn(Map.of("doc-1", snippets));

        List<Document> chunks = docsIngester.splitToChunks(List.of(page));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.getFirst().getText())
                .startsWith("Path: Flow UI > Button\n\nTITLE: Create a Button")
                .contains("<button/>");
        assertThat(chunks.getFirst().getMetadata())
                .containsEntry("enriched", "true")
                .containsEntry("generationKey", "model:p3:verbatim-code-coverage-v1")
                .containsEntry("type", "docs-snippets");
        assertThat(chunks.get(1).getText()).contains("TITLE: Button click handler");
        assertThat(chunks.get(2).getText()).startsWith("Path: Flow UI > Button\n\n")
                .contains("The button component allows users to trigger actions");
        assertThat(chunks.get(2).getMetadata())
                .containsEntry("coverage", "true")
                .containsEntry("generationKey", "model:p3:verbatim-code-coverage-v1")
                .doesNotContainKey("enriched");
    }

    @Test
    void docsSnippets_FallsBackToPlainTextChunkingOnFailure() {
        when(snippetizer.resolveAll(any(), anyList(), any())).thenReturn(Map.of());

        List<Document> chunks = docsIngester.splitToChunks(List.of(docsPage()));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getText()).doesNotContain("TITLE: "));
    }

    @Test
    void docsSnippets_CoverageIsBoundedAndReconstructsSource() {
        String html = "<article class=\"doc\"><p>" + "x".repeat(20_000) + "TAIL</p></article>";
        Document page = new Document("large-doc", html, Map.of(
                "type", "docs-snippets",
                "source", "large.html",
                "sourceHash", "hash-large",
                "jmixVersion", "v2",
                "url", "https://docs/v2/large.html",
                "docPath", "Large page"));
        when(snippetizer.getGenerationKey()).thenReturn("model:p3:verbatim-code-coverage-v1");
        when(snippetizer.resolveAll(any(), anyList(), any())).thenReturn(Map.of(
                "large-doc", List.of(new Snippet("Large", "Summary.", null, null,
                        "https://docs/v2/large.html"))));

        List<Document> chunks = docsIngester.splitToChunks(List.of(page));

        String prefix = "Path: Large page\n\n";
        List<Document> coverage = chunks.stream()
                .filter(chunk -> "true".equals(chunk.getMetadata().get("coverage")))
                .toList();
        assertThat(coverage).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.getText()).startsWith(prefix);
            assertThat(chunk.getText().length())
                    .isLessThanOrEqualTo(SnippetizerEnricher.MAX_COVERAGE_CHARS);
            assertThat(chunk.getMetadata())
                    .containsEntry("generationKey", "model:p3:verbatim-code-coverage-v1")
                    .doesNotContainKey("enriched");
        });
        assertThat(coverage.stream()
                .map(Document::getText)
                .map(text -> text.substring(prefix.length()))
                .collect(Collectors.joining()))
                .isEqualTo(DocsHtmlConverter.toPlainText(html));
    }

    @Test
    void docsSnippets_FallbackKeepsOversizedPlainText() {
        String html = "<article class=\"doc\"><p>" + "x".repeat(35_000) + "TAIL</p></article>";
        Document page = new Document("large-doc", html, Map.of(
                "type", "docs-snippets",
                "source", "large.html",
                "sourceHash", "hash-large",
                "jmixVersion", "v2",
                "url", "https://docs/v2/large.html",
                "docPath", "Large page"));
        when(snippetizer.resolveAll(any(), anyList(), any())).thenReturn(Map.of());

        List<Document> chunks = docsIngester.splitToChunks(List.of(page));

        String prefix = "Path: Large page\n\n";
        assertThat(chunks).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> {
                    assertThat(chunk.getText().length())
                            .isLessThanOrEqualTo(SnippetizerEnricher.MAX_COVERAGE_CHARS);
                    assertThat(chunk.getText()).startsWith(prefix);
                    assertThat(chunk.getMetadata()).doesNotContainKeys("enriched", "generationKey");
                });
        String reconstructed = chunks.stream()
                .map(Document::getText)
                .map(text -> text.substring(prefix.length()))
                .collect(Collectors.joining());
        assertThat(reconstructed).isEqualTo(DocsHtmlConverter.toPlainText(html));
    }

    @Test
    void uiSamplesSnippets_ProducesSnippetDocuments() {
        when(snippetizer.getGenerationKey()).thenReturn("model:p3:verbatim-code-coverage-v1");
        Document page = samplePage();
        when(snippetizer.resolveAll(eq("uisamples-snippets"), anyList(), any()))
                .thenReturn(Map.of("sample-1", List.of(
                        new Snippet("Button sample", "Basic button usage.", "xml", "<button/>", "https://us/v2/sample/button-sample"))));

        List<Document> chunks = uiSamplesIngester.splitToChunks(List.of(page));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().getText()).startsWith("TITLE: Button sample");
        assertThat(chunks.getFirst().getMetadata())
                .containsEntry("enriched", "true")
                .containsEntry("generationKey", "model:p3:verbatim-code-coverage-v1");
        assertThat(chunks.get(1).getText()).isEqualTo(page.getText());
        assertThat(chunks.get(1).getMetadata())
                .containsEntry("coverage", "true")
                .containsEntry("generationKey", "model:p3:verbatim-code-coverage-v1")
                .doesNotContainKey("enriched");
    }

    @Test
    void uiSamplesSnippets_FallsBackToWholeDocumentOnFailure() {
        Document page = samplePage();
        when(snippetizer.resolveAll(any(), anyList(), any())).thenReturn(Map.of());

        List<Document> chunks = uiSamplesIngester.splitToChunks(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText()).isEqualTo(page.getText());
    }

    @Test
    void uiSamplesSnippets_OversizedCoverageIsBoundedAndReconstructsSource() {
        String text = "Path: Samples > Large\n\n" + "x".repeat(35_000) + "TAIL";
        Document page = new Document("large-sample", text, Map.of(
                "type", "uisamples-snippets",
                "source", "large-sample",
                "sourceHash", "hash-large",
                "jmixVersion", "v2",
                "url", "https://us/v2/sample/large-sample"));
        when(snippetizer.getGenerationKey()).thenReturn("model:p3:verbatim-code-coverage-v1");
        when(snippetizer.resolveAll(any(), anyList(), any())).thenReturn(Map.of(
                "large-sample", List.of(new Snippet(
                        "Large sample", "Summary.", null, null,
                        "https://us/v2/sample/large-sample"))));

        List<Document> chunks = uiSamplesIngester.splitToChunks(List.of(page));

        List<Document> coverage = chunks.stream()
                .filter(chunk -> "true".equals(chunk.getMetadata().get("coverage")))
                .toList();
        assertThat(coverage).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.getText().length())
                    .isLessThanOrEqualTo(SnippetizerEnricher.MAX_COVERAGE_CHARS);
            assertThat(chunk.getMetadata())
                    .containsEntry("coverage", "true")
                    .containsEntry("generationKey", "model:p3:verbatim-code-coverage-v1")
                    .doesNotContainKey("enriched");
        });
        assertThat(coverage.stream().map(Document::getText).collect(Collectors.joining()))
                .isEqualTo(text);
    }

    @Test
    void uiSamplesSnippets_FallbackKeepsOversizedDocument() {
        String text = "Path: Samples > Large\n\n" + "x".repeat(35_000) + "TAIL";
        Document page = new Document("large-sample", text, Map.of(
                "type", "uisamples-snippets",
                "source", "large-sample",
                "sourceHash", "hash-large",
                "jmixVersion", "v2",
                "url", "https://us/v2/sample/large-sample"));
        when(snippetizer.resolveAll(any(), anyList(), any())).thenReturn(Map.of());

        List<Document> chunks = uiSamplesIngester.splitToChunks(List.of(page));

        assertThat(chunks).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> {
                    assertThat(chunk.getText().length())
                            .isLessThanOrEqualTo(SnippetizerEnricher.MAX_COVERAGE_CHARS);
                    assertThat(chunk.getMetadata()).doesNotContainKeys("enriched", "generationKey");
                });
        assertThat(chunks.stream().map(Document::getText).collect(Collectors.joining()))
                .isEqualTo(text);
    }
}
