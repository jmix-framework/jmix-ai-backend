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
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractIngesterTest {

    @Mock
    private VectorStore vectorStore;
    
    @Mock
    private TimeSource timeSource;
    
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    
    private TestIngester ingester;
    
    private final UUID mockUuid = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.of(2023, 1, 1, 12, 0);

    @BeforeEach
    void setUp() {
        ingester = new TestIngester(vectorStore, timeSource, vectorStoreRepository);
        ingester = spy(ingester);
        lenient().when(timeSource.now()).thenReturn(now.atZone(ZoneId.systemDefault()));
    }

    @Test
    void shouldComputeHashCorrectly() {
        String content = "Test content";
        String expected = Hashing.murmur3_32_fixed().hashString(content, StandardCharsets.UTF_8).toString();
        
        String hash = ingester.computeHash(content);
        
        assertThat(hash).isEqualTo(expected);
    }

    @Test
    void shouldCreateMetadataCorrectly() {
        String source = "test-source";
        String content = "Test content";
        String hash = ingester.computeHash(content);
        
        Map<String, Object> metadata = ingester.createMetadata(source, content);
        
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
            
            Document document = ingester.createDocument(content, metadata);
            
            assertThat(document.getId()).isEqualTo(mockUuid.toString());
            assertThat(document.getText()).isEqualTo(content);
            assertThat(document.getMetadata()).isEqualTo(metadata);
        }
    }

    @Test
    void shouldBuildCorrectFilterQuery() {
        String source = "test-source";
        String expected = "type == 'test' && source == 'test-source'";
        
        String query = ingester.buildFilterQuery(source);
        
        assertThat(query).isEqualTo(expected);
    }

    @Test
    void shouldDetectContentChanges() {
        Document document = new Document("1", "content", Map.of("sourceHash", "hash1"));
        
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setMetadata("""
                        {"sourceHash": "hash2"}
                        """);
        
        assertThat(ingester.isContentSame(document, entity)).isFalse();
        
        entity.setMetadata("""
                        {"sourceHash": "hash1"}
                        """);
        assertThat(ingester.isContentSame(document, entity)).isTrue();
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
        
        doReturn(sources).when(ingester).loadSources();
        doReturn(0).when(ingester).getSourceLimit();
        doReturn(doc1).when(ingester).loadDocument("source1");
        doReturn(doc2).when(ingester).loadDocument("source2");
        doReturn(true).when(ingester).checkContent(any());
        doReturn(chunks).when(ingester).splitToChunks(List.of(doc1, doc2));
        when(timeSource.currentTimeMillis()).thenReturn(1000L, 5000L);
        
        String result = ingester.updateAll();
        
        verify(ingester).prepareUpdate();
        verify(ingester).loadSources();
        verify(ingester).loadDocument("source1");
        verify(ingester).loadDocument("source2");
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

        doReturn("source1").when(ingester).getSource(entity);
        doReturn(document).when(ingester).loadDocument("source1");
        doReturn(false).when(ingester).isContentSame(document, entity);
        doReturn(chunks).when(ingester).splitToChunks(List.of(document));

        String result = ingester.update(entity);
        
        verify(ingester).prepareUpdate();
        verify(ingester).deleteExistingEntities(entity);
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
        
        doReturn("source1").when(ingester).getSource(entity);
        doReturn(document).when(ingester).loadDocument("source1");
        doReturn(true).when(ingester).isContentSame(document, entity);
        
        String result = ingester.update(entity);
        
        verify(ingester).prepareUpdate();
        verify(ingester, never()).deleteExistingEntities(any());
        verify(vectorStore, never()).add(anyList());
        assertThat(result).isEqualTo("no changes");
    }

    // Test implementation of AbstractIngester
    private static class TestIngester extends AbstractIngester {
        
        public TestIngester(VectorStore vectorStore, TimeSource timeSource, VectorStoreRepository vectorStoreRepository) {
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
        protected List<Document> splitToChunks(List<Document> documents) {
            return List.of();
        }
    }
}