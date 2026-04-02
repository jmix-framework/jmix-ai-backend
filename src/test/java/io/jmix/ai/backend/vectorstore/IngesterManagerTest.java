package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.IngestionJob;
import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngesterManagerTest {

    @Mock
    private Ingester docsIngester;

    @Mock
    private Ingester anotherIngester;

    @Mock
    private KnowledgeSourceManager knowledgeSourceManager;

    private IngesterManager ingesterManager;

    @BeforeEach
    void setUp() {
        lenient().when(docsIngester.getType()).thenReturn("docs");
        lenient().when(anotherIngester.getType()).thenReturn("another");
        lenient().when(knowledgeSourceManager.isEnabled("docs")).thenReturn(true);
        lenient().when(knowledgeSourceManager.isEnabled("another")).thenReturn(true);
        lenient().when(knowledgeSourceManager.resolve("docs")).thenReturn(context("kb-docs", "docs-source", "Docs Source"));
        lenient().when(knowledgeSourceManager.resolve("another")).thenReturn(context("kb-another", "another-source", "Another Source"));
        lenient().when(knowledgeSourceManager.startJob(any())).thenReturn(new IngestionJob());

        ingesterManager = new IngesterManager(Arrays.asList(docsIngester, anotherIngester), knowledgeSourceManager);
    }

    @Test
    void shouldReturnAllTypes() {
        when(docsIngester.getType()).thenReturn("docs");
        when(anotherIngester.getType()).thenReturn("another");

        List<String> types = ingesterManager.getTypes();
        
        assertThat(types).hasSize(2)
                         .containsExactly("docs", "another");
    }

    @Test
    void shouldUpdateAll() {
        when(docsIngester.updateAll()).thenReturn("docs result");
        when(anotherIngester.updateAll()).thenReturn("another result");

        String result = ingesterManager.update();
        
        verify(docsIngester).updateAll();
        verify(anotherIngester).updateAll();
        assertThat(result).contains("Docs Source", "Another Source");
    }

    @Test
    void shouldUpdateByType() {
        when(docsIngester.updateAll()).thenReturn("docs result");
        
        String result = ingesterManager.updateByType("docs");
        
        verify(docsIngester).updateAll();
        verify(anotherIngester, never()).updateAll();
        assertThat(result).contains("docs result");
    }

    @Test
    void shouldUpdateByEntity() {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setMetadata("""
                {
                    "type": "docs",
                    "sourceCode": "docs-source"
                }
                """);
        
        when(docsIngester.update(entity)).thenReturn("docs entity result");
        
        String result = ingesterManager.updateByEntity(entity);
        
        verify(docsIngester).update(entity);
        verify(anotherIngester, never()).update(any());
        assertThat(result).contains("docs entity result");
    }

    private KnowledgeSourceContext context(String kbCode, String sourceCode, String sourceName) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setCode(kbCode);

        KnowledgeSource knowledgeSource = new KnowledgeSource();
        knowledgeSource.setCode(sourceCode);
        knowledgeSource.setName(sourceName);
        knowledgeSource.setKnowledgeBase(knowledgeBase);

        return new KnowledgeSourceContext(knowledgeBase, knowledgeSource);
    }
}
