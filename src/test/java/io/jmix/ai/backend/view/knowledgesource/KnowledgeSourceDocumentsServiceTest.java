package io.jmix.ai.backend.view.knowledgesource;

import io.jmix.ai.backend.entity.KnowledgeDocumentItem;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSourceDocumentsServiceTest {

    @Test
    void loadDocuments_groupsChunksByDocumentPath() {
        UUID sourceId = UUID.randomUUID();
        VectorStoreEntity firstChunk = vectorStoreEntity(UUID.randomUUID(), Map.of(
                "sourceId", sourceId.toString(),
                "documentPath", "uploads/one.md",
                "documentName", "one.md",
                "documentKind", "document",
                "type", "business-documents"
        ));
        VectorStoreEntity secondChunk = vectorStoreEntity(UUID.randomUUID(), Map.of(
                "sourceId", sourceId.toString(),
                "documentPath", "uploads/one.md",
                "documentName", "one.md",
                "documentKind", "document",
                "type", "business-documents"
        ));
        VectorStoreEntity thirdChunk = vectorStoreEntity(UUID.randomUUID(), Map.of(
                "sourceId", sourceId.toString(),
                "documentPath", "uploads/two.md",
                "documentName", "two.md",
                "documentKind", "document",
                "type", "business-documents",
                "url", "https://example.com/two"
        ));

        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        when(dataManager.create(KnowledgeDocumentItem.class)).thenAnswer(invocation -> new KnowledgeDocumentItem());
        VectorStoreRepository vectorStoreRepository = mock(VectorStoreRepository.class);
        KnowledgeSource source = knowledgeSource(sourceId, "business-documents-local");
        when(dataManager.load(KnowledgeSource.class).id(sourceId).one()).thenReturn(source);
        when(vectorStoreRepository.loadList("sourceId == '%s'".formatted(sourceId)))
                .thenReturn(List.of(firstChunk, secondChunk, thirdChunk));

        KnowledgeSourceDocumentsService service =
                new KnowledgeSourceDocumentsService(dataManager, vectorStoreRepository, mock(IngesterManager.class));

        List<KnowledgeDocumentItem> documents = service.loadDocuments(sourceId);

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getDocumentPath()).isEqualTo("uploads/one.md");
        assertThat(documents.get(0).getChunkCount()).isEqualTo(2);
        assertThat(documents.get(1).getExternalUrl()).isEqualTo("https://example.com/two");
    }

    @Test
    void loadDocuments_fallsBackToSourceCodeWhenSourceIdMetadataIsMissing() {
        UUID sourceId = UUID.randomUUID();
        KnowledgeSource source = knowledgeSource(sourceId, "docs-site");
        VectorStoreEntity chunk = vectorStoreEntity(UUID.randomUUID(), Map.of(
                "sourceCode", "docs-site",
                "docPath", "Data access > DataManager",
                "url", "https://docs.example/data-manager.html"
        ));

        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        when(dataManager.create(KnowledgeDocumentItem.class)).thenAnswer(invocation -> new KnowledgeDocumentItem());
        when(dataManager.load(KnowledgeSource.class).id(sourceId).one()).thenReturn(source);
        VectorStoreRepository vectorStoreRepository = mock(VectorStoreRepository.class);
        when(vectorStoreRepository.loadList("sourceId == '%s'".formatted(sourceId))).thenReturn(List.of());
        when(vectorStoreRepository.loadList("sourceCode == 'docs-site'")).thenReturn(List.of(chunk));

        KnowledgeSourceDocumentsService service =
                new KnowledgeSourceDocumentsService(dataManager, vectorStoreRepository, mock(IngesterManager.class));

        List<KnowledgeDocumentItem> documents = service.loadDocuments(sourceId);

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getDocumentPath()).isEqualTo("Data access > DataManager");
        assertThat(documents.getFirst().getExternalUrl()).isEqualTo("https://docs.example/data-manager.html");
    }

    @Test
    void loadDocuments_fallsBackToLegacyTypeWhenSourceMetadataIsMissing() {
        UUID sourceId = UUID.randomUUID();
        KnowledgeSource source = knowledgeSource(sourceId, "docs-site");
        VectorStoreEntity chunk = vectorStoreEntity(UUID.randomUUID(), Map.of(
                "type", "docs",
                "docPath", "Data access > DataManager",
                "source", "data-access/data-manager.html",
                "url", "https://docs.example/data-manager.html"
        ));

        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        when(dataManager.create(KnowledgeDocumentItem.class)).thenAnswer(invocation -> new KnowledgeDocumentItem());
        when(dataManager.load(KnowledgeSource.class).id(sourceId).one()).thenReturn(source);
        VectorStoreRepository vectorStoreRepository = mock(VectorStoreRepository.class);
        when(vectorStoreRepository.loadList("sourceId == '%s'".formatted(sourceId))).thenReturn(List.of());
        when(vectorStoreRepository.loadList("sourceCode == 'docs-site'")).thenReturn(List.of());
        when(vectorStoreRepository.loadList("type == 'docs'")).thenReturn(List.of(chunk));

        KnowledgeSourceDocumentsService service =
                new KnowledgeSourceDocumentsService(dataManager, vectorStoreRepository, mock(IngesterManager.class));

        List<KnowledgeDocumentItem> documents = service.loadDocuments(sourceId);

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getDocumentPath()).isEqualTo("Data access > DataManager");
    }

    @Test
    void deleteDocument_fallsBackToSourceCodeMetadata() {
        UUID sourceId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeDocumentItem item = new KnowledgeDocumentItem();
        item.setKnowledgeSourceId(sourceId);
        item.setDocumentPath("Data access > DataManager");
        item.setRepresentativeVectorStoreId(chunkId);

        DataManager dataManager = mock(DataManager.class);
        VectorStoreRepository vectorStoreRepository = mock(VectorStoreRepository.class);
        when(vectorStoreRepository.load(chunkId)).thenReturn(vectorStoreEntity(chunkId, Map.of(
                "sourceCode", "docs-site",
                "docPath", "Data access > DataManager"
        )));
        List<VectorStoreEntity> deletedChunks = List.of(vectorStoreEntity(UUID.randomUUID(), Map.of()));
        when(vectorStoreRepository.loadList("sourceCode == 'docs-site' && source == 'Data access > DataManager'"))
                .thenReturn(deletedChunks);

        KnowledgeSourceDocumentsService service =
                new KnowledgeSourceDocumentsService(dataManager, vectorStoreRepository, mock(IngesterManager.class));

        service.deleteDocument(item);

        verify(vectorStoreRepository).delete(deletedChunks);
    }

    @Test
    void deleteDocument_fallsBackToLegacyTypeAndSource() {
        UUID sourceId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeDocumentItem item = new KnowledgeDocumentItem();
        item.setKnowledgeSourceId(sourceId);
        item.setRepresentativeVectorStoreId(chunkId);

        DataManager dataManager = mock(DataManager.class);
        VectorStoreRepository vectorStoreRepository = mock(VectorStoreRepository.class);
        when(vectorStoreRepository.load(chunkId)).thenReturn(vectorStoreEntity(chunkId, Map.of(
                "type", "docs",
                "docPath", "Data access > DataManager",
                "source", "data-access/data-manager.html"
        )));
        List<VectorStoreEntity> deletedChunks = List.of(vectorStoreEntity(UUID.randomUUID(), Map.of()));
        when(vectorStoreRepository.loadList("type == 'docs' && source == 'data-access/data-manager.html'"))
                .thenReturn(deletedChunks);

        KnowledgeSourceDocumentsService service =
                new KnowledgeSourceDocumentsService(dataManager, vectorStoreRepository, mock(IngesterManager.class));

        service.deleteDocument(item);

        verify(vectorStoreRepository).delete(deletedChunks);
    }

    private KnowledgeSource knowledgeSource(UUID sourceId, String code) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(sourceId);
        source.setCode(code);
        source.setName(code);
        return source;
    }

    private VectorStoreEntity vectorStoreEntity(UUID id, Map<String, Object> metadataMap) {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setId(id);
        entity.setMetadata(toJson(metadataMap));
        return entity;
    }

    private String toJson(Map<String, Object> metadataMap) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : metadataMap.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append('"')
                    .append(':')
                    .append('"').append(entry.getValue()).append('"');
        }
        sb.append('}');
        return sb.toString();
    }
}
