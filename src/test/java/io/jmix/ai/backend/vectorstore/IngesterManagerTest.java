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
class IngesterManagerTest {

    @Mock
    private Ingester docsIngester;

    @Mock
    private Ingester anotherIngester;

    private IngesterManager ingesterManager;

    @BeforeEach
    void setUp() {
        lenient().when(docsIngester.getType()).thenReturn("docs");
        lenient().when(anotherIngester.getType()).thenReturn("another");

        ingesterManager = new IngesterManager(Arrays.asList(docsIngester, anotherIngester));
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
        String result = ingesterManager.update();
        
        verify(docsIngester).updateAll();
        verify(anotherIngester).updateAll();
        assertThat(result).contains(List.of("docs", "another"));
    }

    @Test
    void shouldUpdateByType() {
        when(docsIngester.updateAll()).thenReturn("docs result");
        
        String result = ingesterManager.updateByType("docs");
        
        verify(docsIngester).updateAll();
        verifyNoInteractions(anotherIngester);
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
        
        when(docsIngester.update(entity)).thenReturn("docs entity result");
        
        String result = ingesterManager.updateByEntity(entity);
        
        verify(docsIngester).update(entity);
        verifyNoInteractions(anotherIngester);
        assertThat(result).contains("docs entity result");
    }
}