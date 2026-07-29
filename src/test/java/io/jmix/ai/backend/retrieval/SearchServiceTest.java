package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private ParametersRepository parametersRepository;
    @Mock
    private ToolsManager toolsManager;

    private static Document doc(String id, double score) {
        return Document.builder().id(id).text(id).score(score).build();
    }

    /** Two tools, each fills the shared pool from its own corpus when executed. */
    @SuppressWarnings("unchecked")
    private void stubTools(List<Document> firstToolDocs, List<Document> secondToolDocs) {
        Parameters parameters = mock(Parameters.class);
        when(parameters.getContent()).thenReturn("");
        when(parametersRepository.loadActive(ParametersTargetType.SEARCH)).thenReturn(parameters);
        when(toolsManager.getTools(any(), any(), any(), any())).thenAnswer(invocation -> {
            List<Document> pool = invocation.getArgument(1);
            AbstractRagTool first = mock(AbstractRagTool.class);
            AbstractRagTool second = mock(AbstractRagTool.class);
            when(first.execute(any(), any())).thenAnswer(call -> {
                pool.addAll(firstToolDocs);
                return "";
            });
            when(second.execute(any(), any())).thenAnswer(call -> {
                pool.addAll(secondToolDocs);
                return "";
            });
            return List.of(first, second);
        });
    }

    @Test
    void capsTotalResultsAcrossToolsByRelevance() {
        stubTools(
                List.of(doc("a", 0.9), doc("b", 0.5), doc("c", 0.1)),
                List.of(doc("d", 0.8), doc("e", 0.4), doc("f", 0.2)));
        SearchService service = new SearchService(parametersRepository, toolsManager);

        List<Document> result = service.search("query", JmixVersion.V2, 4);

        // globally most relevant 4 across both corpora, not 4 per tool
        assertThat(result).extracting(Document::getId).containsExactly("a", "d", "b", "e");
    }

    @Test
    void withoutMaxResultsReturnsEveryRankedDocument() {
        stubTools(
                List.of(doc("a", 0.9), doc("b", 0.5)),
                List.of(doc("c", 0.8)));
        SearchService service = new SearchService(parametersRepository, toolsManager);

        List<Document> result = service.search("query", JmixVersion.V2, null);

        assertThat(result).extracting(Document::getId).containsExactly("a", "c", "b");
    }

    /**
     * Mixed configuration: a fixed-pipeline tool ignores {@code maxResults} and may overfill the
     * pool with its configured count, an adaptive tool honors it — the global trim still caps the
     * response at {@code maxResults} of the most relevant documents.
     */
    @Test
    void fixedToolOverfillIsStillTrimmedGlobally() {
        stubTools(
                List.of(doc("l1", 0.9), doc("l2", 0.8), doc("l3", 0.7), doc("l4", 0.6), doc("l5", 0.3)),
                List.of(doc("a1", 0.85), doc("a2", 0.2)));
        SearchService service = new SearchService(parametersRepository, toolsManager);

        List<Document> result = service.search("query", JmixVersion.V2, 3);

        assertThat(result).extracting(Document::getId).containsExactly("l1", "a1", "l2");
    }

    @Test
    void maxResultsAbovePoolSizeReturnsAll() {
        stubTools(List.of(doc("a", 0.9)), List.of(doc("b", 0.8)));
        SearchService service = new SearchService(parametersRepository, toolsManager);

        List<Document> result = service.search("query", JmixVersion.V2, 50);

        assertThat(result).extracting(Document::getId).containsExactly("a", "b");
    }
}
