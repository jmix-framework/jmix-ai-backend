package io.jmix.ai.backend.vectorstore.snippets;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
import io.jmix.ai.backend.vectorstore.Snippet;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SnippetizerEnricherTest {

    private static final Document PAGE = new Document("1",
            "<article><h2>Buttons</h2><p>How to create a button</p><pre>&lt;button text=\"OK\"/&gt;</pre></article>",
            Map.of("type", "docs-snippets",
                    "source", "flow-ui/vc/components/button.html",
                    "jmixVersion", "v2",
                    "sourceHash", "hash1",
                    "url", "https://docs.jmix.io/jmix/flow-ui/vc/components/button.html",
                    "docPath", "Flow UI > Visual Components > Button"));

    private static class TestSnippetizer extends SnippetizerEnricher {
        final ChatModel chatModel;
        Prompt capturedPrompt;
        final List<Prompt> capturedPrompts = new ArrayList<>();

        TestSnippetizer(ChatModel chatModel) {
            this(chatModel, mock(EnrichmentCacheRepository.class));
        }

        TestSnippetizer(ChatModel chatModel, EnrichmentCacheRepository enrichmentCacheRepository) {
            super("test-model", "low", 4, "test-key",
                    Duration.ofSeconds(1), Duration.ofSeconds(1), enrichmentCacheRepository);
            this.chatModel = chatModel;
        }

        @Override
        protected ChatModel createChatModel() {
            return prompt -> {
                capturedPrompt = prompt;
                capturedPrompts.add(prompt);
                return chatModel.call(prompt);
            };
        }
    }

    @Test
    void snippetize_ParsesResponseAndSendsPage() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"snippets": [
                  {"title": "Create\\na Button", "description": "Shows how to declare a button.", "language": "xml", "code": "<button text=\\"OK\\"/>"},
                  {"title": "", "description": "blank title must be filtered", "language": "", "code": ""},
                  {"title": "Button styling", "description": "Style  a\\nbutton.", "language": "", "code": ""}
                ]}
                """));
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel);

        List<Snippet> snippets = snippetizer.snippetize(PAGE, DocsHtmlConverter.toPlainText(PAGE.getText()));

        assertThat(snippets).hasSize(2);
        assertThat(snippets.getFirst().title()).isEqualTo("Create a Button");
        assertThat(snippets.getFirst().code()).isEqualTo("<button text=\"OK\"/>");
        assertThat(snippets.getFirst().language()).isEqualTo("xml");
        assertThat(snippets.getFirst().absoluteUrl()).isEqualTo("https://docs.jmix.io/jmix/flow-ui/vc/components/button.html");
        assertThat(snippets.get(1).description()).isEqualTo("Style a button.");
        assertThat(snippets.get(1).code()).isNull();
        assertThat(snippetizer.capturedPrompt.getUserMessage().getText())
                .contains("Flow UI > Visual Components > Button")
                .contains("How to create a button");
    }

    @Test
    void snippetize_ReturnsNullOnFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        assertThat(new TestSnippetizer(chatModel).snippetize(PAGE, PAGE.getText())).isNull();
    }

    @Test
    void snippetize_ReturnsNullOnEmptySnippets() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("{\"snippets\": []}"));

        assertThat(new TestSnippetizer(chatModel).snippetize(PAGE, PAGE.getText())).isNull();
    }

    @Test
    void jsonRoundTrip_BindsSnippetsToTheCurrentUrl() {
        TestSnippetizer snippetizer = new TestSnippetizer(mock(ChatModel.class));
        List<Snippet> snippets = List.of(
                new Snippet("T1", "D1", "java", "int a = 1;", "https://old.example.com/1"),
                new Snippet("T2", "D2", null, null, "https://old.example.com/2"));

        assertThat(snippetizer.fromJson(snippetizer.toJson(snippets), "https://example.com/current"))
                .containsExactly(
                        new Snippet("T1", "D1", "java", "int a = 1;", "https://example.com/current"),
                        new Snippet("T2", "D2", null, null, "https://example.com/current"));
    }

    @Test
    void fromJson_NormalizesCachedTitleToOneLine() {
        TestSnippetizer snippetizer = new TestSnippetizer(mock(ChatModel.class));
        String json = snippetizer.toJson(List.of(
                new Snippet("Create\n  a Button", "D", null, null, "https://example.com")));

        assertThat(snippetizer.fromJson(json, "https://example.com")).singleElement()
                .extracting(Snippet::title)
                .isEqualTo("Create a Button");
    }

    @Test
    void fromJson_BindsPayloadWithoutUrlToTheCurrentUrl() {
        TestSnippetizer snippetizer = new TestSnippetizer(mock(ChatModel.class));
        // a legacy cached payload predating the absoluteUrl field deserializes it as null
        String json = "[{\"title\": \"T\", \"description\": \"D\", \"language\": null, \"code\": null}]";

        assertThat(snippetizer.fromJson(json, "https://example.com/current")).singleElement()
                .extracting(Snippet::absoluteUrl)
                .isEqualTo("https://example.com/current");
    }

    @Test
    void modelKeyIncludesReasoningEffort() {
        assertThat(new TestSnippetizer(mock(ChatModel.class)).getModelKey()).isEqualTo("test-model:low:prompt-version-2026-07-28");
        assertThat(new TestSnippetizer(mock(ChatModel.class)).getGenerationKey())
                .isEqualTo("test-model:low:prompt-version-2026-07-28:snippet-format-version-2026-07-26");
    }

    @Test
    void snippetize_DropsOnlyTheSnippetWithModifiedCode() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"snippets": [
                  {"title": "Honest code", "description": "Copied verbatim.", "language": "xml", "code": "<button text=\\"OK\\"/>"},
                  {"title": "Reformatted", "description": "Model changed the spacing.", "language": "xml", "code": "<button   text=\\"OK\\"/>"},
                  {"title": "Text only", "description": "No code at all.", "language": "", "code": ""}
                ]}
                """));

        List<Snippet> snippets = new TestSnippetizer(chatModel).snippetize(
                PAGE, DocsHtmlConverter.toPlainText(PAGE.getText()));

        assertThat(snippets).extracting(Snippet::title)
                .containsExactly("Honest code", "Text only");
    }

    @Test
    void snippetize_RejectsCodeThatIsNotPresentInSource() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"snippets": [{"title": "Invented", "description": "Not grounded.",
                  "language": "xml", "code": "<button text=\\"Invented\\"/>"}]}
                """));

        List<Snippet> snippets = new TestSnippetizer(chatModel).snippetize(
                PAGE, DocsHtmlConverter.toPlainText(PAGE.getText()));

        assertThat(snippets).isNull();
    }

    @Test
    void resolveAll_RegeneratesCachedSnippetWithModifiedCode() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"snippets": [{"title": "Button", "description": "Grounded.",
                  "language": "xml", "code": "<button text=\\"OK\\"/>"}]}
                """));
        EnrichmentCacheRepository cacheRepository = mock(EnrichmentCacheRepository.class);
        EnrichmentCache cached = new EnrichmentCache();
        cached.setContentHash("hash1");
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel, cacheRepository);
        cached.setContent(snippetizer.toJson(List.of(
                new Snippet("Invented", "Cached.", "xml", "<button text=\"Invented\"/>", "source"))));
        when(cacheRepository.find("docs-snippets", "flow-ui/vc/components/button.html", JmixVersion.V2,
                "test-model:low:prompt-version-2026-07-28")).thenReturn(Optional.of(cached));

        try {
            Map<String, List<Snippet>> result = snippetizer.resolveAll("docs-snippets", List.of(PAGE),
                    document -> DocsHtmlConverter.toPlainText(document.getText()));

            assertThat(result.get(PAGE.getId())).singleElement()
                    .extracting(Snippet::code)
                    .isEqualTo("<button text=\"OK\"/>");
        } finally {
            snippetizer.shutdownExecutor();
        }
    }

    @Test
    void resolveAll_RegeneratesWhenCachedSnippetsFailTheLiveGate() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"snippets": [{"title": "Button", "description": "Grounded.",
                  "language": "xml", "code": "<button text=\\"OK\\"/>"}]}
                """));
        EnrichmentCacheRepository cacheRepository = mock(EnrichmentCacheRepository.class);
        EnrichmentCache cached = new EnrichmentCache();
        cached.setContentHash("hash1");
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel, cacheRepository);
        // the live path would filter a blank-title snippet, so the cached list must be a miss too
        cached.setContent(snippetizer.toJson(List.of(
                new Snippet(" ", "Blank title fails the gate.", "xml", "<button text=\"OK\"/>", "source"))));
        when(cacheRepository.find("docs-snippets", "flow-ui/vc/components/button.html", JmixVersion.V2,
                "test-model:low:prompt-version-2026-07-28")).thenReturn(Optional.of(cached));

        try {
            Map<String, List<Snippet>> result = snippetizer.resolveAll("docs-snippets", List.of(PAGE),
                    document -> DocsHtmlConverter.toPlainText(document.getText()));

            assertThat(result.get(PAGE.getId())).singleElement()
                    .extracting(Snippet::title)
                    .isEqualTo("Button");
        } finally {
            snippetizer.shutdownExecutor();
        }
    }

    @Test
    void resolveAll_ReusesValidCachedSnippetWithoutCallingModel() {
        ChatModel chatModel = mock(ChatModel.class);
        EnrichmentCacheRepository cacheRepository = mock(EnrichmentCacheRepository.class);
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel, cacheRepository);
        EnrichmentCache cached = new EnrichmentCache();
        cached.setContentHash("hash1");
        // the URL cached at generation time is stale: the page has moved since
        cached.setContent(snippetizer.toJson(List.of(
                new Snippet("Button", "Cached.", "xml", "<button text=\"OK\"/>",
                        "https://old.docs.example/button.html"))));
        when(cacheRepository.find("docs-snippets", "flow-ui/vc/components/button.html", JmixVersion.V2,
                "test-model:low:prompt-version-2026-07-28")).thenReturn(Optional.of(cached));

        try {
            Map<String, List<Snippet>> result = snippetizer.resolveAll("docs-snippets", List.of(PAGE),
                    document -> DocsHtmlConverter.toPlainText(document.getText()));

            assertThat(result.get(PAGE.getId())).singleElement()
                    .isEqualTo(new Snippet("Button", "Cached.", "xml", "<button text=\"OK\"/>",
                            "https://docs.jmix.io/jmix/flow-ui/vc/components/button.html"));
            verifyNoInteractions(chatModel);
        } finally {
            snippetizer.shutdownExecutor();
        }
    }

    @Test
    void resolveAll_SubmitsAtMostParallelismUntilWorkCompletes() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        EnrichmentCacheRepository cacheRepository = mock(EnrichmentCacheRepository.class);
        when(cacheRepository.find(any(), any(), any(), any())).thenReturn(Optional.empty());
        CountDownLatch firstWindowStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            firstWindowStarted.countDown();
            release.await();
            return chatResponse("""
                    {"snippets": [{"title": "Page", "description": "Processed.", "language": "", "code": ""}]}
                    """);
        });
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel, cacheRepository);
        List<Document> pages = List.of(page("1"), page("2"), page("3"), page("4"), page("5"));
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            Future<Map<String, List<Snippet>>> update = caller.submit(
                    () -> snippetizer.resolveAll("docs-snippets", pages, Document::getText));
            assertThat(firstWindowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(chatModel, times(4)).call(any(Prompt.class));

            release.countDown();

            assertThat(update.get(5, TimeUnit.SECONDS)).hasSize(5);
            verify(chatModel, times(5)).call(any(Prompt.class));
        } finally {
            release.countDown();
            caller.shutdownNow();
            snippetizer.shutdownExecutor();
        }
    }

    @Test
    void verbatimValidationRejectsEmptySnippetList() {
        assertThat(SnippetizerEnricher.containsOnlyVerbatimCode(List.of(), "source")).isFalse();
    }

    @Test
    void splitContent_PreservesEveryCharacterAndPrefersParagraphBoundaries() {
        String content = "first paragraph\n\n" + "x".repeat(35) + "\nlast line";

        List<String> parts = SnippetizerEnricher.splitContent(content, 20);

        assertThat(parts).allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(20));
        assertThat(String.join("", parts)).isEqualTo(content);
        assertThat(parts.getFirst()).endsWith("\n\n");
    }

    @Test
    void snippetize_ProcessesTailBeyondSingleRequestLimitWithoutLosingInput() {
        String content = "Head paragraph.\n\n".repeat(4_000) + "TAIL_MARKER";
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            boolean tail = prompt.getUserMessage().getText().contains("TAIL_MARKER");
            return chatResponse("""
                    {"snippets": [{"title": "%s", "description": "Processed.", "language": "", "code": ""}]}
                    """.formatted(tail ? "Tail" : "Head"));
        });
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel);

        List<Snippet> snippets = snippetizer.snippetize(PAGE, content);

        assertThat(snippetizer.capturedPrompts).hasSizeGreaterThan(1);
        assertThat(snippets).extracting(Snippet::title).contains("Tail");
        String submittedContent = snippetizer.capturedPrompts.stream()
                .map(prompt -> prompt.getUserMessage().getText())
                .map(text -> text.substring(text.indexOf("Page content:\n") + "Page content:\n".length()))
                .collect(Collectors.joining());
        assertThat(submittedContent).isEqualTo(content);
        assertThat(snippetizer.capturedPrompts).allSatisfy(prompt -> {
            String text = prompt.getUserMessage().getText();
            String part = text.substring(text.indexOf("Page content:\n") + "Page content:\n".length());
            assertThat(part.length()).isLessThanOrEqualTo(SnippetizerEnricher.MAX_INPUT_CHARS);
        });
    }

    @Test
    void snippetize_DiscardsPartialResultWhenOnePartFails() {
        String content = "x".repeat(SnippetizerEnricher.MAX_INPUT_CHARS * 2) + "TAIL";
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("""
                        {"snippets": [{"title": "Head", "description": "Processed.", "language": "", "code": ""}]}
                        """))
                .thenThrow(new RuntimeException("tail failed"));
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel);

        assertThat(snippetizer.snippetize(PAGE, content)).isNull();
        assertThat(snippetizer.capturedPrompts).hasSize(2);
    }

    private static Document page(String id) {
        return new Document(id, PAGE.getText(), Map.of(
                "type", "docs-snippets",
                "source", "page-" + id,
                "sourceHash", "hash-" + id,
                "url", "https://example.com/" + id));
    }

    private static ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
