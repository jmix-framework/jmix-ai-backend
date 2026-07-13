package io.jmix.ai.backend.vectorstore;

import com.google.common.hash.Hashing;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.TimeSource;
import io.jmix.core.UuidProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
        Document doc1 = new Document("1", "content1", Map.of("source", "source1", "sourceHash", "hash1"));
        Document doc2 = new Document("2", "content2", Map.of("source", "source2", "sourceHash", "hash2"));
        List<Document> chunks = List.of(
                new Document("3", "chunk1", Map.of("type", "test", "source", "source1")),
                new Document("4", "chunk2", Map.of("type", "test", "source", "source1")),
                new Document("5", "chunk3", Map.of("type", "test", "source", "source2"))
        );
        
        doReturn(sources).when(ingester).loadSources(null);
        doReturn(0).when(ingester).getSourceLimit();
        doReturn(doc1).when(ingester).loadDocument("source1", null);
        doReturn(doc2).when(ingester).loadDocument("source2", null);
        doReturn(chunks).when(ingester).splitToChunks(List.of(doc1, doc2));
        when(vectorStoreRepository.loadList(anyString())).thenReturn(List.of());
        when(timeSource.currentTimeMillis()).thenReturn(1000L, 5000L);

        String result = ingester.updateAll();

        verify(ingester).prepareUpdate(null);
        verify(ingester).loadSources(null);
        verify(ingester).loadDocument("source1", null);
        verify(ingester).loadDocument("source2", null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(chunksCaptor.capture());
        assertThat(chunksCaptor.getValue()).allSatisfy(chunk ->
                assertThat(chunk.getMetadata()).containsKeys("ingestionId", "ingestionChunkCount"));
        assertThat(chunksCaptor.getValue())
                .filteredOn(chunk -> "source1".equals(chunk.getMetadata().get("source")))
                .allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsEntry("ingestionChunkCount", 2));
        assertThat(chunksCaptor.getValue())
                .filteredOn(chunk -> "source2".equals(chunk.getMetadata().get("source")))
                .allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsEntry("ingestionChunkCount", 1));
        assertThat(result).isEqualTo("loaded: 2, added: 2 documents in 3 chunks");
    }

    @Test
    void shouldRequireVersionForVersionScopedUpdate() {
        TestIngester versionedIngester = new TestIngester(
                vectorStore, timeSource, vectorStoreRepository, true);

        assertThatThrownBy(versionedIngester::updateAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Jmix version is required to update version-scoped ingester 'test'");

        verifyNoInteractions(vectorStore, vectorStoreRepository);
    }

    @Test
    void shouldUpdateSingleEntity() {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(UUID.randomUUID());
        entity.setMetadata("""
                        {
                            "type": "test",
                            "source": "source1",
                            "sourceHash": "oldHash"
                        }
                        """
                );
        
        Document document = new Document("1", "content", Map.of("source", "source1", "sourceHash", "hash1"));
        List<Document> chunks = List.of(new Document("2", "chunk", Map.of("type", "test", "source", "source1")));

        doReturn(document).when(ingester).loadDocument("source1", null);
        doReturn(chunks).when(ingester).splitToChunks(List.of(document));
        when(vectorStoreRepository.loadList(anyString())).thenReturn(List.of(entity));

        String result = ingester.update(entity);
        
        verify(ingester).prepareUpdate();
        InOrder inOrder = inOrder(vectorStore, vectorStoreRepository);
        inOrder.verify(vectorStore).add(anyList());
        inOrder.verify(vectorStoreRepository).deleteIds(List.of(entity.getId()));
        assertThat(result).isEqualTo("updated 1 document");
    }

    @Test
    void shouldSkipUpdateWhenContentIsUnchanged() {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(UUID.randomUUID());
        entity.setMetadata("""
                        {
                            "type": "test",
                            "source": "source1",
                            "sourceHash": "hash1"
                        }
                        """);
        
        Document document = new Document("1", "content", Map.of("source", "source1", "sourceHash", "hash1"));
        
        doReturn(document).when(ingester).loadDocument("source1", null);
        when(vectorStoreRepository.loadList(anyString())).thenReturn(List.of(entity));
        
        String result = ingester.update(entity);
        
        verify(ingester).prepareUpdate();
        verify(vectorStoreRepository, never()).deleteIds(anyList());
        verify(vectorStoreRepository, never()).delete(anyString());
        verify(vectorStore, never()).add(anyList());
        assertThat(result).isEqualTo("no changes");
    }

    @Test
    void shouldKeepPreviousGenerationAndRemovePartialNewGenerationWhenAddFails() {
        VectorStoreEntity entity = entity("source1", "oldHash");
        Document document = new Document("1", "content", Map.of("source", "source1", "sourceHash", "newHash"));
        Document chunk = new Document("2", "chunk", Map.of("type", "test", "source", "source1"));

        doReturn(document).when(ingester).loadDocument("source1", null);
        doReturn(List.of(chunk)).when(ingester).splitToChunks(List.of(document));
        when(vectorStoreRepository.loadList(anyString())).thenReturn(List.of(entity));
        doThrow(new IllegalStateException("vector store unavailable")).when(vectorStore).add(anyList());

        assertThatThrownBy(() -> ingester.update(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("vector store unavailable");

        verify(vectorStoreRepository, never()).deleteIds(anyList());
        verify(vectorStoreRepository).delete(argThat((String filter) ->
                filter.contains("type == 'test'") && filter.contains("ingestionId == '")));
    }

    @Test
    void shouldKeepPreviousGenerationWhenNoChunksWereGenerated() {
        VectorStoreEntity entity = entity("source1", "oldHash");
        Document document = new Document("1", "content", Map.of("source", "source1", "sourceHash", "newHash"));

        doReturn(document).when(ingester).loadDocument("source1", null);
        doReturn(List.of()).when(ingester).splitToChunks(List.of(document));
        when(vectorStoreRepository.loadList(anyString())).thenReturn(List.of(entity));

        assertThat(ingester.update(entity)).isEqualTo("not updated: no chunks generated");

        verify(vectorStore, never()).add(anyList());
        verify(vectorStoreRepository, never()).deleteIds(anyList());
        verify(vectorStoreRepository, never()).delete(anyString());
    }

    @Test
    void shouldUpdateOtherSourcesWhenOneSourceGeneratesNoChunks() {
        VectorStoreEntity firstOld = entity("source1", "oldHash1");
        VectorStoreEntity secondOld = entity("source2", "oldHash2");
        Document first = new Document(
                "1", "content1", Map.of("source", "source1", "sourceHash", "newHash1"));
        Document second = new Document(
                "2", "content2", Map.of("source", "source2", "sourceHash", "newHash2"));
        Document firstChunk = new Document(
                "3", "chunk1", Map.of("type", "test", "source", "source1"));

        doReturn(List.of("source1", "source2")).when(ingester).loadSources(null);
        doReturn(first).when(ingester).loadDocument("source1", null);
        doReturn(second).when(ingester).loadDocument("source2", null);
        doReturn(List.of(firstChunk)).when(ingester).splitToChunks(List.of(first, second));
        when(vectorStoreRepository.loadList("type == 'test' && source == 'source1'"))
                .thenReturn(List.of(firstOld));
        when(vectorStoreRepository.loadList("type == 'test' && source == 'source2'"))
                .thenReturn(List.of(secondOld));

        String result = ingester.updateAll();

        verify(vectorStore).add(anyList());
        verify(vectorStoreRepository).deleteIds(List.of(firstOld.getId()));
        verify(vectorStoreRepository, never()).deleteIds(List.of(secondOld.getId()));
        assertThat(result).isEqualTo("loaded: 2, added: 1 documents in 1 chunks");
    }

    private VectorStoreEntity entity(String source, String sourceHash) {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(UUID.randomUUID());
        entity.setMetadata("""
                {
                    "type": "test",
                    "source": "%s",
                    "sourceHash": "%s"
                }
                """.formatted(source, sourceHash));
        return entity;
    }

    // Test implementation of AbstractIngester
    private static class TestIngester extends AbstractIngester {
        
        public TestIngester(VectorStore vectorStore, TimeSource timeSource, VectorStoreRepository vectorStoreRepository) {
            this(vectorStore, timeSource, vectorStoreRepository, false);
        }

        public TestIngester(VectorStore vectorStore, TimeSource timeSource,
                            VectorStoreRepository vectorStoreRepository, boolean versionScoped) {
            super(vectorStore, timeSource, vectorStoreRepository, versionScoped);
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
