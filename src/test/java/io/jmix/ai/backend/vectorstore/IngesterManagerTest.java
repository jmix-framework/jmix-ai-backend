package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.JmixVersion;
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
        when(docsIngester.getVersions()).thenReturn(List.of());
        when(anotherIngester.getVersions()).thenReturn(List.of());
        when(docsIngester.updateAll()).thenReturn("docs result");
        when(anotherIngester.updateAll()).thenReturn("another result");

        String result = ingesterManager.update();

        verify(docsIngester).updateAll();
        verify(anotherIngester).updateAll();
        assertThat(result).contains(List.of("docs", "another"));
    }

    @Test
    void shouldUpdateByType() {
        lenient().when(docsIngester.getVersions()).thenReturn(List.of());
        when(docsIngester.updateAll()).thenReturn("docs result");

        String result = ingesterManager.updateByType("docs");

        verify(docsIngester).updateAll();
        verifyNoInteractions(anotherIngester);
        assertThat(result).contains("docs result");
    }

    @Test
    void shouldUpdateByTypeAndVersion() {
        lenient().when(docsIngester.getVersions()).thenReturn(List.of(JmixVersion.V2, JmixVersion.V3));
        when(docsIngester.updateAll(JmixVersion.V2)).thenReturn("docs v2 result");

        String result = ingesterManager.updateByTypeAndVersion("docs", JmixVersion.V2);

        verify(docsIngester).updateAll(JmixVersion.V2);
        verifyNoInteractions(anotherIngester);
        assertThat(result).contains("docs v2 result");
    }

    @Test
    void shouldUpdateAllRunningEveryVersionForVersionedIngesters() {
        when(docsIngester.getVersions()).thenReturn(List.of(JmixVersion.V2, JmixVersion.V3));
        when(docsIngester.updateAll(JmixVersion.V2)).thenReturn("docs v2");
        when(docsIngester.updateAll(JmixVersion.V3)).thenReturn("docs v3");
        when(anotherIngester.getVersions()).thenReturn(List.of());
        when(anotherIngester.updateAll()).thenReturn("another result");

        String result = ingesterManager.update();

        verify(docsIngester).updateAll(JmixVersion.V2);
        verify(docsIngester).updateAll(JmixVersion.V3);
        verify(anotherIngester).updateAll();
        assertThat(result).contains("docs v2", "docs v3", "another result");
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
