package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
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

class RerankerTest {

    @Test
    void rerank_SortsAndClampsScores() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"results":[
                  {"index":2,"score":2.0},
                  {"index":0,"score":0.4},
                  {"index":1,"score":-1.0}
                ]}
                """));

        TestReranker reranker = new TestReranker(chatModel);
        List<Document> documents = List.of(
                new Document("0", "doc-0", Map.of("source", "zero")),
                new Document("1", "doc-1", Map.of("source", "one")),
                new Document("2", "doc-2", Map.of("source", "two"))
        );

        List<Reranker.Result> results = reranker.rerank("query", documents, 2, new ParametersReader(Map.of()));

        assertThat(results)
                .extracting(result -> result.document().getId(), Reranker.Result::score)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2", 1.0),
                        org.assertj.core.groups.Tuple.tuple("0", 0.4)
                );
    }

    @Test
    void rerank_ReadsSettingsSkipsBlankDocsAndPreservesOriginalIndices() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"results":[{"index":1,"score":0.7}]}
                """));

        TestReranker reranker = new TestReranker(chatModel);
        ParametersReader parametersReader = new ParametersReader(Map.of(
                "reranker", Map.of(
                        "model", "custom-model",
                        "temperature", 0.25,
                        "maxDocumentChars", 5
                )
        ));

        List<Document> documents = List.of(
                new Document("0", "   ", Map.of("source", "blank")),
                new Document("1", "abcdef", Map.of("source", "kept"))
        );

        List<Reranker.Result> results = reranker.rerank("query", documents, 1, parametersReader);

        assertThat(reranker.capturedOptions.model()).isEqualTo("custom-model");
        assertThat(reranker.capturedOptions.temperature()).isEqualTo(0.25);
        assertThat(reranker.capturedOptions.maxDocumentChars()).isEqualTo(5);
        assertThat(results).singleElement().extracting(result -> result.document().getId()).isEqualTo("1");

        UserMessage userMessage = reranker.capturedPrompt.getUserMessage();
        assertThat(userMessage.getText()).contains("\"index\":1");
        assertThat(userMessage.getText()).contains("abcde");
        assertThat(userMessage.getText()).doesNotContain("abcdef");
        assertThat(userMessage.getText()).doesNotContain("\"index\":0");
    }

    @Test
    void rerank_ReturnsNullWhenModelFails() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        TestReranker reranker = new TestReranker(chatModel);
        List<Document> documents = List.of(new Document("1", "doc", Map.of("source", "one")));

        List<Reranker.Result> results = reranker.rerank("query", documents, 1, new ParametersReader(Map.of()));

        assertThat(results).isNull();
    }

    @Test
    void rerank_ReturnsNullWhenResponseIsMalformed() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("not-json"));

        TestReranker reranker = new TestReranker(chatModel);
        List<Document> documents = List.of(new Document("1", "doc", Map.of("source", "one")));

        List<Reranker.Result> results = reranker.rerank("query", documents, 1, new ParametersReader(Map.of()));

        assertThat(results).isNull();
    }

    @Test
    void rerank_ReturnsNullWhenResponseContainsInvalidIndices() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"results":[{"index":99,"score":0.7}]}
                """));

        TestReranker reranker = new TestReranker(chatModel);
        List<Document> documents = List.of(new Document("1", "doc", Map.of("source", "one")));

        List<Reranker.Result> results = reranker.rerank("query", documents, 1, new ParametersReader(Map.of()));

        assertThat(results).isNull();
    }

    private static ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(content))));
    }

    private static class TestReranker extends Reranker {
        private final ChatModel chatModel;
        private RerankerOptions capturedOptions;
        private Prompt capturedPrompt;

        private TestReranker(ChatModel chatModel) {
            super("test-api-key");
            this.chatModel = chatModel;
        }

        @Override
        protected ChatModel buildChatModel(RerankerOptions options) {
            this.capturedOptions = options;
            return prompt -> {
                this.capturedPrompt = prompt;
                return chatModel.call(prompt);
            };
        }
    }
}
