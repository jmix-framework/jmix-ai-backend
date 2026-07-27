package io.jmix.ai.backend.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.parameters.ParametersReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractRagToolTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private PostRetrievalProcessor postRetrievalProcessor;
    @Mock
    private Reranker reranker;
    @Mock
    private ToolEventListener listener;

    @Test
    void vectorTypeParameterOverridesCorpusType() {
        ParametersReader reader = reader(Map.of("vectorType", "docs-snippets"));

        DocsTool tool = tool(reader, new ArrayList<>());

        assertThat(tool.type).isEqualTo("docs-snippets");
    }

    @Test
    void typeDefaultsToBuiltInWithoutOverride() {
        ParametersReader reader = reader(Map.of());

        DocsTool tool = tool(reader, new ArrayList<>());

        assertThat(tool.type).isEqualTo("docs");
    }

    @Test
    void appendsConfiguredAverageSizeToToolDescription() {
        DocsTool tool = tool(reader(Map.of("averageDocumentTokens", 321)), new ArrayList<>());

        assertThat(tool.getToolCallback().getToolDefinition().description())
                .isEqualTo("docs tool A typical returned snippet is about 321 tokens.");
    }

    @Test
    void schemaMakesMaxResultsOptionalAndAdvertisesCap() throws Exception {
        DocsTool tool = tool(reader(Map.of("averageDocumentTokens", 300)), new ArrayList<>());

        JsonNode schema = new ObjectMapper().readTree(
                tool.getToolCallback().getToolDefinition().inputSchema());

        assertThat(schema.path("properties").has("queryText")).isTrue();
        assertThat(schema.path("properties").has("maxResults")).isTrue();
        assertThat(schema.path("required").toString()).isEqualTo("[\"queryText\"]");
        assertThat(schema.path("properties").path("maxResults").path("description").asText())
                .contains("1-50", "configured default", "approximate token size");
    }

    @Test
    void nullMaxResultsUsesConfiguredRetrievalAndResultCounts() {
        ParametersReader reader = reader(Map.of("topK", 10, "topReranked", 3));
        List<Document> retrievedDocuments = new ArrayList<>();
        DocsTool tool = tool(reader, retrievedDocuments);
        List<Document> candidates = prepareCandidates(4);
        when(reranker.rerank("query", candidates, 3, reader))
                .thenReturn(List.of(new Reranker.Result(candidates.getFirst(), 1.0)));

        tool.execute("query", null);

        verifySearchTopK(20);
        verify(reranker).rerank("query", candidates, 3, reader);
        assertThat(retrievedDocuments).containsExactly(candidates.getFirst());
    }

    @Test
    void searchFiltersBySelectedCorpusTypeAndJmixVersion() {
        ParametersReader reader = reader(Map.of(
                "vectorType", "docs-snippets",
                "topK", 10,
                "topReranked", 3));
        DocsTool tool = tool(reader, new ArrayList<>());
        List<Document> candidates = prepareCandidates(1);
        when(reranker.rerank("query", candidates, 3, reader))
                .thenReturn(List.of(new Reranker.Result(candidates.getFirst(), 1.0)));

        tool.execute("query", null);

        org.mockito.ArgumentCaptor<SearchRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        assertThat(requestCaptor.getValue().getFilterExpression()).isEqualTo(
                fb.and(
                        fb.eq("type", "docs-snippets"),
                        fb.eq("jmixVersion", JmixVersion.V2.getId()))
                        .build());
    }

    @Test
    void maxResultsIsCappedAtFiftyAndWidensCandidatePool() {
        ParametersReader reader = reader(Map.of("topK", 10, "topReranked", 3));
        DocsTool tool = tool(reader, new ArrayList<>());
        List<Document> candidates = prepareCandidates(4);
        when(reranker.rerank("query", candidates, 50, reader))
                .thenReturn(List.of(new Reranker.Result(candidates.getFirst(), 1.0)));

        tool.execute("query", 500);

        verifySearchTopK(200);
        verify(reranker).rerank("query", candidates, 50, reader);
    }

    @Test
    void rerankerFallbackKeepsAllDocumentsPassingMinScore() {
        ParametersReader reader = reader(Map.of("topK", 10, "topReranked", 2));
        List<Document> retrievedDocuments = new ArrayList<>();
        DocsTool tool = tool(reader, retrievedDocuments);
        List<Document> candidates = prepareCandidates(5);
        when(reranker.rerank("query", candidates, 2, reader)).thenReturn(null);

        String result = tool.execute("query", null);

        assertThat(retrievedDocuments).containsExactlyElementsOf(candidates);
        assertThat(result).isEqualTo("text-0\n\ntext-1\n\ntext-2\n\ntext-3\n\ntext-4");
    }

    @Test
    void rerankerFallbackRespectsExplicitMaxResults() {
        ParametersReader reader = reader(Map.of("topK", 4, "topReranked", 2));
        List<Document> retrievedDocuments = new ArrayList<>();
        DocsTool tool = tool(reader, retrievedDocuments);
        List<Document> candidates = prepareCandidates(6);
        when(reranker.rerank("query", candidates, 3, reader)).thenReturn(null);

        String result = tool.execute("query", 3);

        verifySearchTopK(12);
        assertThat(retrievedDocuments).containsExactlyElementsOf(candidates.subList(0, 3));
        assertThat(result).isEqualTo("text-0\n\ntext-1\n\ntext-2");
    }

    private ParametersReader reader(Map<String, Object> overrides) {
        Map<String, Object> toolParameters = new HashMap<>();
        toolParameters.put("description", "docs tool");
        toolParameters.put("similarityThreshold", 0.0);
        toolParameters.put("topK", 10);
        toolParameters.put("topReranked", 3);
        toolParameters.put("minScore", 0.0);
        toolParameters.put("minRerankedScore", 0.0);
        toolParameters.putAll(overrides);
        return new ParametersReader(Map.of(
                "tools", Map.of("documentation_retriever", toolParameters)));
    }

    /**
     * One page's snippets must not flood the candidate pool: at most
     * {@link AbstractRagTool#MAX_CHUNKS_PER_SOURCE} chunks per source survive, the freed slots
     * are refilled by other pages, and the similarity order is preserved.
     */
    @Test
    @SuppressWarnings("unchecked")
    void capsFloodedSourcePageBeforeReranking() {
        ParametersReader reader = reader(Map.of("topK", 4, "topReranked", 2));
        DocsTool tool = tool(reader, new ArrayList<>());
        List<Document> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(Document.builder().id("flood-" + i).text("flood-" + i)
                    .metadata(Map.of("source", "flooding.html")).score(1.0 - i * 0.01).build());
        }
        candidates.add(Document.builder().id("other").text("other")
                .metadata(Map.of("source", "other.html")).score(0.5).build());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
        when(postRetrievalProcessor.process(eq("query"), same(candidates))).thenReturn(candidates);
        when(reranker.rerank(eq("query"), anyList(), eq(2), eq(reader))).thenReturn(List.of());

        tool.execute("query", null);

        org.mockito.ArgumentCaptor<List<Document>> rerankInput =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(eq("query"), rerankInput.capture(), eq(2), eq(reader));
        assertThat(rerankInput.getValue()).extracting(Document::getId)
                .containsExactly("flood-0", "flood-1", "flood-2", "other");
    }

    private DocsTool tool(ParametersReader reader, List<Document> retrievedDocuments) {
        return new DocsTool(vectorStore, postRetrievalProcessor, reranker, reader,
                retrievedDocuments, listener, JmixVersion.V2);
    }

    private List<Document> prepareCandidates(int count) {
        List<Document> candidates = IntStream.range(0, count)
                .mapToObj(index -> Document.builder()
                        .id("id-" + index)
                        .text("text-" + index)
                        .score(1.0 - index * 0.01)
                        .build())
                .toList();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
        when(postRetrievalProcessor.process(eq("query"), same(candidates))).thenReturn(candidates);
        return candidates;
    }

    private void verifySearchTopK(int expected) {
        org.mockito.ArgumentCaptor<SearchRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(expected);
    }
}
