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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetrieverSpecificBehaviorTest {

    @Mock
    private VectorStore vectorStore;
    
    @Mock
    private TimeSource timeSource;
    
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    
    @Test
    void shouldCheckContentAndSkipExistingUnchangedDocuments() {
        TestRetriever retriever = spy(new TestRetriever(vectorStore, timeSource, vectorStoreRepository));
        Document document = new Document("1", "content", Map.of("source", "test", "sourceHash", "hash1"));
        
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setMetadata("""
                        {"sourceHash": "hash1"}
                        """);
        List<VectorStoreEntity> entities = List.of(entity);
        
        when(vectorStoreRepository.loadList(any())).thenReturn(entities);
        
        boolean result = retriever.checkContent(document);
        
        verify(vectorStoreRepository).loadList(any());
        verify(vectorStoreRepository, never()).delete(any(UUID.class));
        assertThat(result).isFalse();
    }

    @Test
    void shouldDeleteAndReplaceChangedDocuments() {
        TestRetriever retriever = spy(new TestRetriever(vectorStore, timeSource, vectorStoreRepository));
        Document document = new Document("1", "content", Map.of("source", "test", "sourceHash", "newHash"));
        
        UUID entityId = UUID.randomUUID();
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(entityId);
        entity.setMetadata("""
                        {"sourceHash": "oldHash"}
                        """);
        List<VectorStoreEntity> entities = List.of(entity);
        
        when(vectorStoreRepository.loadList(any())).thenReturn(entities);
        
        boolean result = retriever.checkContent(document);
        
        verify(vectorStoreRepository).loadList(any());
        verify(vectorStoreRepository).delete(entityId);
        assertThat(result).isTrue();
    }
    
    // Test implementation of AbstractRetriever
    private static class TestRetriever extends AbstractRetriever {
        
        public TestRetriever(VectorStore vectorStore, TimeSource timeSource, VectorStoreRepository vectorStoreRepository) {
            super(vectorStore, timeSource, vectorStoreRepository);
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
        protected boolean checkContent(Document document) {
            return super.checkContent(document);
        }
    }
}