package io.jmix.ai.backend.view.knowledgebase;

import io.jmix.ai.backend.entity.IngestionJob;
import io.jmix.ai.backend.entity.KnowledgeDocumentItem;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.view.knowledgesource.KnowledgeSourceDocumentsService;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

class KnowledgeBaseWorkspaceServiceTest {

    @Test
    void loadDocuments_aggregatesDocumentsAcrossSourcesAndSetsSourceName() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID alphaSourceId = UUID.randomUUID();
        UUID betaSourceId = UUID.randomUUID();

        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        KnowledgeSourceDocumentsService documentsService = mock(KnowledgeSourceDocumentsService.class);

        KnowledgeSource alphaSource = knowledgeSource(alphaSourceId, "Alpha source");
        KnowledgeSource betaSource = knowledgeSource(betaSourceId, "Beta source");

        when(dataManager.load(KnowledgeSource.class)
                .query("select e from KnowledgeSource e where e.knowledgeBase.id = :knowledgeBaseId order by e.name")
                .parameter("knowledgeBaseId", knowledgeBaseId)
                .list())
                .thenReturn(List.of(alphaSource, betaSource));

        when(documentsService.loadDocuments(alphaSourceId)).thenReturn(List.of(document("docs/a.md")));
        when(documentsService.loadDocuments(betaSourceId)).thenReturn(List.of(document("docs/b.md")));

        KnowledgeBaseWorkspaceService service = new KnowledgeBaseWorkspaceService(dataManager, documentsService);

        List<KnowledgeDocumentItem> documents = service.loadDocuments(knowledgeBaseId);

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getSourceName()).isEqualTo("Alpha source");
        assertThat(documents.get(0).getDocumentPath()).isEqualTo("docs/a.md");
        assertThat(documents.get(1).getSourceName()).isEqualTo("Beta source");
        assertThat(documents.get(1).getDocumentPath()).isEqualTo("docs/b.md");
    }

    @Test
    void loadJobs_returnsJobsForKnowledgeBaseOrderedByStartedAt() {
        UUID knowledgeBaseId = UUID.randomUUID();
        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        KnowledgeSourceDocumentsService documentsService = mock(KnowledgeSourceDocumentsService.class);

        IngestionJob first = new IngestionJob();
        IngestionJob second = new IngestionJob();

        when(dataManager.load(IngestionJob.class)
                .query("select e from IngestionJob e where e.knowledgeBase.id = :knowledgeBaseId order by e.startedAt desc")
                .parameter("knowledgeBaseId", knowledgeBaseId)
                .list())
                .thenReturn(List.of(first, second));

        KnowledgeBaseWorkspaceService service = new KnowledgeBaseWorkspaceService(dataManager, documentsService);

        assertThat(service.loadJobs(knowledgeBaseId)).containsExactly(first, second);
    }

    private KnowledgeSource knowledgeSource(UUID id, String name) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setName(name);
        return source;
    }

    private KnowledgeDocumentItem document(String documentPath) {
        KnowledgeDocumentItem item = new KnowledgeDocumentItem();
        item.setDocumentPath(documentPath);
        return item;
    }
}
