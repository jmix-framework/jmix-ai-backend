package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.TimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngesterSpecificBehaviorTest {

    @Mock
    private VectorStore vectorStore;
    
    @Mock
    private TimeSource timeSource;
    
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    
    @Test
    void shouldKeepCompleteCurrentGeneration() {
        TestIngester ingester = spy(new TestIngester(vectorStore, timeSource, vectorStoreRepository));
        Document document = new Document("1", "content", Map.of("source", "test", "sourceHash", "hash1"));
        
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(UUID.randomUUID());
        entity.setMetadata("""
                        {
                            "source": "test",
                            "sourceHash": "hash1",
                            "ingestionId": "complete-run",
                            "ingestionChunkCount": 1
                        }
                        """);
        VectorStoreEntity oldEntity = new VectorStoreEntity();
        oldEntity.setId(UUID.randomUUID());
        oldEntity.setMetadata("""
                {
                    "source": "test",
                    "sourceHash": "old-hash"
                }
                """);
        doReturn(List.of("test")).when(ingester).loadSources(null);
        doReturn(document).when(ingester).loadDocument("test", null);
        when(vectorStoreRepository.loadList(anyString())).thenReturn(List.of(entity, oldEntity));

        String result = ingester.updateAll();

        verify(vectorStore, never()).add(anyList());
        verify(vectorStoreRepository, never()).delete(anyString());
        verify(vectorStoreRepository).deleteIds(List.of(oldEntity.getId()));
        assertThat(result).isEqualTo("loaded: 1, added: 0 documents in 0 chunks");
    }

    @Test
    void shouldReplaceIncompleteCurrentGeneration() {
        TestIngester ingester = spy(new TestIngester(vectorStore, timeSource, vectorStoreRepository));
        Document document = new Document("1", "content", Map.of("source", "test", "sourceHash", "hash1"));
        Document chunk = new Document("2", "chunk", Map.of("type", "test", "source", "test"));
        
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(UUID.randomUUID());
        entity.setMetadata("""
                        {
                            "source": "test",
                            "sourceHash": "hash1",
                            "ingestionId": "partial-run",
                            "ingestionChunkCount": 2
                        }
                        """);
        doReturn(List.of("test")).when(ingester).loadSources(null);
        doReturn(document).when(ingester).loadDocument("test", null);
        doReturn(List.of(chunk)).when(ingester).splitToChunks(List.of(document));
        when(vectorStoreRepository.loadList(anyString())).thenReturn(List.of(entity));

        String result = ingester.updateAll();

        verify(vectorStore).add(anyList());
        verify(vectorStoreRepository).deleteIds(List.of(entity.getId()));
        assertThat(result).isEqualTo("loaded: 1, added: 1 documents in 1 chunks");
    }
    
    // Test implementation of AbstractIngester
    private static class TestIngester extends AbstractIngester {
        
        public TestIngester(VectorStore vectorStore, TimeSource timeSource, VectorStoreRepository vectorStoreRepository) {
            super(vectorStore, timeSource, vectorStoreRepository, false);
        }

        @Override
        public String getType() {
            return "test";
        }

        @Override
        protected List<String> loadSources() {
            return Collections.emptyList();
        }

        @Override
        protected int getSourceLimit() {
            return 0;
        }

        @Override
        protected Document loadDocument(String source) {
            return null;
        }

        @Override
        protected List<Document> splitToChunks(List<Document> documents) {
            return List.of();
        }
    }
}
