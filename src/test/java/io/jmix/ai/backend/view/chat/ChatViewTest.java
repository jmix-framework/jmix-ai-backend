package io.jmix.ai.backend.view.chat;

import io.jmix.ai.backend.entity.Parameters;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatViewTest {

    @Test
    void resolveFriendlyChatError_returnsModelSpecificMessageForMissingOllamaModel() {
        Parameters parameters = new Parameters();
        parameters.setContent("""
                spring:
                  ai:
                    openai:
                      chat:
                        options:
                          model: qwen3-coder:30b
                """);

        RuntimeException ex = new RuntimeException("404 model 'qwen3-coder:30b' not found");

        assertThat(ChatView.resolveFriendlyChatError(ex, parameters))
                .isEqualTo("chatView.modelNotAvailable|qwen3-coder:30b");
    }

    @Test
    void resolveFriendlyChatError_returnsNullForUnrelatedErrors() {
        Parameters parameters = new Parameters();
        parameters.setContent("model:\n  name: qwen3-coder:30b\n");

        assertThat(ChatView.resolveFriendlyChatError(new IllegalStateException("500 boom"), parameters)).isNull();
    }

    @Test
    void isKnowledgeDocumentPreviewable_returnsTrueForLocalKbDocument() {
        Document document = new Document("alfa-trade-summary.md", "text", Map.of(
                "kb", "business-documents-demo",
                "sourceCode", "business-documents-local",
                "source", "summaries/alfa-trade-summary.md",
                "documentPath", "summaries/alfa-trade-summary.md",
                "type", "business-documents"
        ));

        assertThat(ChatView.isVectorStoreDocumentLinkable(document)).isFalse();
        assertThat(ChatView.isKnowledgeDocumentPreviewable(document)).isTrue();
        assertThat(ChatView.getDocumentPreviewText(document)).isEqualTo("summaries/alfa-trade-summary.md");
    }

    @Test
    void isVectorStoreDocumentLinkable_returnsTrueForVectorStoreUuidDocument() {
        String id = UUID.randomUUID().toString();
        Document document = new Document(id, "text", Map.of(
                "source", "docs/data-manager.md",
                "type", "docs"
        ));

        assertThat(ChatView.isVectorStoreDocumentLinkable(document)).isTrue();
        assertThat(ChatView.isKnowledgeDocumentPreviewable(document)).isFalse();
    }

    @Test
    void isKnowledgeDocumentPreviewable_returnsFalseForUrlOnlyDocument() {
        Document document = new Document("docs-page", "text", Map.of(
                "url", "https://docs.jmix.ru/1.x/jmix/1.7/data-access/data-manager.html",
                "type", "docs"
        ));

        assertThat(ChatView.isKnowledgeDocumentPreviewable(document)).isFalse();
    }

    @Test
    void resolveConversationId_prefersRequestValue() {
        assertThat(ChatView.resolveConversationId("requested-id", "session-id"))
                .isEqualTo("requested-id");
    }

    @Test
    void resolveConversationId_fallsBackToSessionValue() {
        assertThat(ChatView.resolveConversationId("   ", "session-id"))
                .isEqualTo("session-id");
    }

    @Test
    void resolveConversationId_generatesValueWhenNothingProvided() {
        assertThat(ChatView.resolveConversationId(null, null)).isNotBlank();
    }
}
