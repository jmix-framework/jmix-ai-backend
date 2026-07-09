package io.jmix.ai.backend.vectorstore.snippets;

import io.jmix.ai.backend.vectorstore.EnrichmentCacheRepository;
import io.jmix.ai.backend.vectorstore.Snippet;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnippetizerEnricherTest {

    private static final Document PAGE = new Document("1",
            "<article><h2>Buttons</h2><p>How to create a button</p><pre>&lt;button text=\"OK\"/&gt;</pre></article>",
            Map.of("type", "docs-snippets",
                    "source", "flow-ui/vc/components/button.html",
                    "sourceHash", "hash1",
                    "url", "https://docs.jmix.io/jmix/flow-ui/vc/components/button.html",
                    "docPath", "Flow UI > Visual Components > Button"));

    private static class TestSnippetizer extends SnippetizerEnricher {
        final ChatModel chatModel;
        Prompt capturedPrompt;

        TestSnippetizer(ChatModel chatModel) {
            super("test-model", "low", 4, "test-key", mock(EnrichmentCacheRepository.class));
            this.chatModel = chatModel;
        }

        @Override
        protected ChatModel buildChatModel() {
            return prompt -> {
                capturedPrompt = prompt;
                return chatModel.call(prompt);
            };
        }
    }

    @Test
    void snippetize_ParsesResponseAndSendsPage() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"snippets": [
                  {"title": "Create a Button", "description": "Shows how to declare a button.", "language": "xml", "code": "<button text=\\"OK\\"/>"},
                  {"title": "", "description": "blank title must be filtered", "language": "", "code": ""},
                  {"title": "Button styling", "description": "Style  a\\nbutton.", "language": "", "code": ""}
                ]}
                """));
        TestSnippetizer snippetizer = new TestSnippetizer(chatModel);

        List<Snippet> snippets = snippetizer.snippetize(PAGE, PAGE.getText());

        assertThat(snippets).hasSize(2);
        assertThat(snippets.get(0).title()).isEqualTo("Create a Button");
        assertThat(snippets.get(0).code()).isEqualTo("<button text=\"OK\"/>");
        assertThat(snippets.get(0).language()).isEqualTo("xml");
        assertThat(snippets.get(0).source()).isEqualTo("https://docs.jmix.io/jmix/flow-ui/vc/components/button.html");
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
    void jsonRoundTrip() {
        TestSnippetizer snippetizer = new TestSnippetizer(mock(ChatModel.class));
        List<Snippet> snippets = List.of(
                new Snippet("T1", "D1", "java", "int a = 1;", "https://example.com/1"),
                new Snippet("T2", "D2", null, null, "https://example.com/2"));

        assertThat(snippetizer.fromJson(snippetizer.toJson(snippets))).isEqualTo(snippets);
    }

    @Test
    void modelKeyIncludesReasoningEffort() {
        assertThat(new TestSnippetizer(mock(ChatModel.class)).getModelKey()).isEqualTo("test-model:low:p2");
    }

    private static ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
