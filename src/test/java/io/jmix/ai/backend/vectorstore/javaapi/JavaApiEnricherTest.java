package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.vectorstore.Snippet;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaApiEnricherTest {

    private static final Snippet CARD = new Snippet(
            "Interface DataManager (io.jmix.core)",
            "Same as UnconstrainedDataManager but performs authorization of all operations.",
            "java",
            "public interface DataManager extends UnconstrainedDataManager\n\n// Methods\nUnconstrainedDataManager unconstrained()",
            "https://docs.jmix.io/api/2.8/io/jmix/core/DataManager.html");

    private static class TestEnricher extends JavaApiEnricher {
        final ChatModel chatModel;
        Prompt capturedPrompt;

        TestEnricher(ChatModel chatModel) {
            super(false, "test-model", "low", "test-key");
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
    void enrich_ParsesResponseAndSendsCard() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"description": "Authorization-aware data access facade.", "example": "DataManager dm;\\ndm.unconstrained();"}
                """));
        TestEnricher enricher = new TestEnricher(chatModel);

        JavaApiEnricher.Enrichment enrichment = enricher.enrich(CARD.format());

        assertThat(enrichment).isNotNull();
        assertThat(enrichment.description()).isEqualTo("Authorization-aware data access facade.");
        assertThat(enrichment.example()).contains("unconstrained()");
        assertThat(enricher.capturedPrompt.getUserMessage().getText()).contains("Interface DataManager");
    }

    @Test
    void enrich_ReturnsNullOnFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        assertThat(new TestEnricher(chatModel).enrich(CARD.format())).isNull();
    }

    @Test
    void enrich_ReturnsNullOnBlankDescription() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"description": "", "example": "x"}
                """));

        assertThat(new TestEnricher(chatModel).enrich(CARD.format())).isNull();
    }

    @Test
    void assembleCard_ReplacesDescriptionAndAppendsExample() {
        JavaApiEnricher.Enrichment enrichment = new JavaApiEnricher.Enrichment(
                "Authorization-aware\ndata access facade.", "DataManager dm;");

        String assembled = JavaApiEnricher.assembleCard(CARD, enrichment);

        assertThat(assembled)
                .contains("TITLE: Interface DataManager (io.jmix.core)")
                .contains("DESCRIPTION: Authorization-aware data access facade.")
                .doesNotContain("Same as UnconstrainedDataManager")
                .contains("public interface DataManager extends UnconstrainedDataManager")
                .contains("// Usage example:\nDataManager dm;");
    }

    @Test
    void assembleCard_FallsBackToDeterministicCard() {
        assertThat(JavaApiEnricher.assembleCard(CARD, null)).isEqualTo(CARD.format());
        assertThat(JavaApiEnricher.assembleCard(CARD, new JavaApiEnricher.Enrichment(" ", "x")))
                .isEqualTo(CARD.format());
    }

    private static ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
