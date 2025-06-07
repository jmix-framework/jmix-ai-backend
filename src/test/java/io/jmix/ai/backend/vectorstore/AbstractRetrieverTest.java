package io.jmix.ai.backend.vectorstore;

import com.google.common.hash.Hashing;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.TimeSource;
import io.jmix.core.UuidProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractRetrieverTest {

    @Mock
    private VectorStore vectorStore;
    
    @Mock
    private TimeSource timeSource;
    
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    
    @Mock
    private TextSplitter textSplitter;
    
    private TestRetriever retriever;
    
    private final UUID mockUuid = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.of(2023, 1, 1, 12, 0);

    @BeforeEach
    void setUp() {
        retriever = new TestRetriever(vectorStore, timeSource, vectorStoreRepository, textSplitter);
        retriever = spy(retriever);
        lenient().when(timeSource.now()).thenReturn(now.atZone(ZoneId.systemDefault()));
    }

    @Test
    void shouldComputeHashCorrectly() {
        String content = "Test content";
        String expected = Hashing.murmur3_32_fixed().hashString(content, StandardCharsets.UTF_8).toString();
        
        String hash = retriever.computeHash(content);
        
        assertThat(hash).isEqualTo(expected);
    }

    @Test
    void shouldCreateMetadataCorrectly() {
        String source = "test-source";
        String content = "Test content";
        String hash = retriever.computeHash(content);
        
        Map<String, Object> metadata = retriever.createMetadata(source, content);
        
        assertThat(metadata)
            .containsEntry("type", "test")
            .containsEntry("source", source)
            .containsEntry("sourceHash", hash)
            .containsEntry("size", content.length())
            .containsEntry("updated", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    @Test
    void shouldCreateDocumentCorrectly() {
        try (MockedStatic<UuidProvider> uuidProvider = mockStatic(UuidProvider.class)) {
            uuidProvider.when(UuidProvider::createUuidV7).thenReturn(mockUuid);
            
            String content = "Test content";
            Map<String, Object> metadata = Map.of("key", "value");
            
            Document document = retriever.createDocument(content, metadata);
            
            assertThat(document.getId()).isEqualTo(mockUuid.toString());
            assertThat(document.getText()).isEqualTo(content);
            assertThat(document.getMetadata()).isEqualTo(metadata);
        }
    }

    @Test
    void shouldBuildCorrectFilterQuery() {
        String source = "test-source";
        String expected = "type == 'test' && source == 'test-source'";
        
        String query = retriever.buildFilterQuery(source);
        
        assertThat(query).isEqualTo(expected);
    }

    @Test
    void shouldDetectContentChanges() {
        Document document = new Document("1", "content", Map.of("sourceHash", "hash1"));
        
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setMetadata("""
                        {"sourceHash": "hash2"}
                        """);
        
        assertThat(retriever.isContentSame(document, entity)).isFalse();
        
        entity.setMetadata("""
                        {"sourceHash": "hash1"}
                        """);
        assertThat(retriever.isContentSame(document, entity)).isTrue();
    }

    @Test
    void shouldUpdateAllDocuments() {
        List<String> sources = List.of("source1", "source2");
        Document doc1 = new Document("1", "content1", Map.of("source", "source1"));
        Document doc2 = new Document("2", "content2", Map.of("source", "source2"));
        List<Document> chunks = List.of(
                new Document("3", "chunk1", Map.of()),
                new Document("4", "chunk2", Map.of())
        );
        
        doReturn(sources).when(retriever).loadSources();
        doReturn(0).when(retriever).getSourceLimit();
        doReturn(doc1).when(retriever).loadDocument("source1");
        doReturn(doc2).when(retriever).loadDocument("source2");
        doReturn(true).when(retriever).checkContent(any());
        when(textSplitter.apply(anyList())).thenReturn(chunks);
        when(timeSource.currentTimeMillis()).thenReturn(1000L, 5000L);
        
        String result = retriever.updateAll();
        
        verify(retriever).prepareUpdate();
        verify(retriever).loadSources();
        verify(retriever).loadDocument("source1");
        verify(retriever).loadDocument("source2");
        verify(textSplitter).apply(List.of(doc1, doc2));
        verify(vectorStore).add(chunks);
        assertThat(result).isEqualTo("loaded: 2, added: 2 documents in 2 chunks");
    }

    @Test
    void shouldUpdateSingleEntity() {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(UUID.randomUUID());
        entity.setMetadata("""
                        {
                            "type": "test",
                            "source": "source1"
                        }
                        """
                );
        
        Document document = new Document("1", "content", Map.of("sourceHash", "hash1"));
        List<Document> chunks = List.of(new Document("2", "chunk", Map.of()));
        
        doReturn("source1").when(retriever).getSource(entity);
        doReturn(document).when(retriever).loadDocument("source1");
        doReturn(false).when(retriever).isContentSame(document, entity);
        when(textSplitter.apply(anyList())).thenReturn(chunks);
        
        String result = retriever.update(entity);
        
        verify(retriever).prepareUpdate();
        verify(retriever).deleteExistingEntities(entity);
        verify(textSplitter).apply(List.of(document));
        verify(vectorStore).add(chunks);
        assertThat(result).isEqualTo("updated 1 document");
    }

    @Test
    void shouldSkipUpdateWhenContentIsUnchanged() {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(UUID.randomUUID());
        entity.setMetadata("""
                        {
                            "type": "test",
                            "source": "source1"
                        }
                        """);
        
        Document document = new Document("1", "content", Map.of("sourceHash", "hash1"));
        
        doReturn("source1").when(retriever).getSource(entity);
        doReturn(document).when(retriever).loadDocument("source1");
        doReturn(true).when(retriever).isContentSame(document, entity);
        
        String result = retriever.update(entity);
        
        verify(retriever).prepareUpdate();
        verify(retriever, never()).deleteExistingEntities(any());
        verify(textSplitter, never()).apply(anyList());
        verify(vectorStore, never()).add(anyList());
        assertThat(result).isEqualTo("no changes");
    }

    // Test implementation of AbstractRetriever
    private static class TestRetriever extends AbstractRetriever {
        
        public TestRetriever(VectorStore vectorStore, TimeSource timeSource, VectorStoreRepository vectorStoreRepository, TextSplitter textSplitter) {
            super(vectorStore, timeSource, vectorStoreRepository);
            // Override the textSplitter field directly
            this.textSplitter = textSplitter;
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
    }
}