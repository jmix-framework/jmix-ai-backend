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
        Document page = docsPage();
        List<Snippet> snippets = List.of(
                new Snippet("Create a Button", "How to declare a button.", "xml", "<button/>", "https://docs/v2/flow-ui/button.html"),
                new Snippet("Button click handler", "Subscribe to click.", "java", "@Subscribe void onClick() {}", "https://docs/v2/flow-ui/button.html"));
        when(snippetizer.resolveAll(eq("docs-snippets"), anyList()))
                .thenReturn(Map.of("doc-1", snippets));

        List<Document> chunks = docsIngester.splitToChunks(List.of(page));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText())
                .startsWith("Path: Flow UI > Button\n\nTITLE: Create a Button")
                .contains("<button/>");
        assertThat(chunks.get(0).getMetadata())
                .containsEntry("enriched", "true")
                .containsEntry("type", "docs-snippets");
        assertThat(chunks.get(1).getText()).contains("TITLE: Button click handler");
    }

    @Test
    void docsSnippets_FallsBackToRegularChunkingOnFailure() {
        when(snippetizer.resolveAll(any(), anyList())).thenReturn(Map.of());

        List<Document> chunks = docsIngester.splitToChunks(List.of(docsPage()));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getText()).doesNotContain("TITLE: "));
    }

    @Test
    void uiSamplesSnippets_ProducesSnippetDocuments() {
        Document page = samplePage();
        when(snippetizer.resolveAll(eq("uisamples-snippets"), anyList()))
                .thenReturn(Map.of("sample-1", List.of(
                        new Snippet("Button sample", "Basic button usage.", "xml", "<button/>", "https://us/v2/sample/button-sample"))));

        List<Document> chunks = uiSamplesIngester.splitToChunks(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).startsWith("TITLE: Button sample");
        assertThat(chunks.get(0).getMetadata()).containsEntry("enriched", "true");
    }

    @Test
    void uiSamplesSnippets_FallsBackToWholeDocumentOnFailure() {
        Document page = samplePage();
        when(snippetizer.resolveAll(any(), anyList())).thenReturn(Map.of());

        List<Document> chunks = uiSamplesIngester.splitToChunks(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo(page.getText());
    }
}
