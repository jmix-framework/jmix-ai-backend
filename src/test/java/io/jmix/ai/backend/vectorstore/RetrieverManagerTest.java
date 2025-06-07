package io.jmix.ai.backend.vectorstore;

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
class RetrieverManagerTest {

    @Mock
    private Retriever docsRetriever;

    @Mock
    private Retriever anotherRetriever;

    private RetrieverManager retrieverManager;

    @BeforeEach
    void setUp() {
        lenient().when(docsRetriever.getType()).thenReturn("docs");
        lenient().when(anotherRetriever.getType()).thenReturn("another");

        retrieverManager = new RetrieverManager(Arrays.asList(docsRetriever, anotherRetriever));
    }

    @Test
    void shouldReturnAllTypes() {
        when(docsRetriever.getType()).thenReturn("docs");
        when(anotherRetriever.getType()).thenReturn("another");

        List<String> types = retrieverManager.getTypes();
        
        assertThat(types).hasSize(2)
                         .containsExactly("docs", "another");
    }

    @Test
    void shouldUpdateAll() {
        String result = retrieverManager.update();
        
        verify(docsRetriever).updateAll();
        verify(anotherRetriever).updateAll();
        assertThat(result).contains(List.of("docs", "another"));
    }

    @Test
    void shouldUpdateByType() {
        when(docsRetriever.updateAll()).thenReturn("docs result");
        
        String result = retrieverManager.updateByType("docs");
        
        verify(docsRetriever).updateAll();
        verifyNoInteractions(anotherRetriever);
        assertThat(result).contains("docs result");
    }

    @Test
    void shouldUpdateByEntity() {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setMetadata("""
                {
                    "type": "docs"
                }
                """);
        
        when(docsRetriever.update(entity)).thenReturn("docs entity result");
        
        String result = retrieverManager.updateByEntity(entity);
        
        verify(docsRetriever).update(entity);
        verifyNoInteractions(anotherRetriever);
        assertThat(result).contains("docs entity result");
    }
}