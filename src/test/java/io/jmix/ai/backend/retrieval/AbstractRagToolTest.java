package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        ParametersReader reader = new ParametersReader(Map.of(
                "tools", Map.of("documentation_retriever", Map.of(
                        "description", "docs tool",
                        "vectorType", "docs-snippets"))));

        DocsTool tool = new DocsTool(vectorStore, postRetrievalProcessor, reranker, reader,
                new ArrayList<>(), listener);

        assertThat(tool.type).isEqualTo("docs-snippets");
    }

    @Test
    void typeDefaultsToBuiltInWithoutOverride() {
        ParametersReader reader = new ParametersReader(Map.of(
                "tools", Map.of("documentation_retriever", Map.of(
                        "description", "docs tool"))));

        DocsTool tool = new DocsTool(vectorStore, postRetrievalProcessor, reranker, reader,
                new ArrayList<>(), listener);

        assertThat(tool.type).isEqualTo("docs");
    }
}
