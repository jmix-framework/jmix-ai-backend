package io.jmix.ai.backend.view.knowledgebase;

import io.jmix.ai.backend.entity.IngestionJob;
import io.jmix.ai.backend.entity.KnowledgeDocumentItem;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.view.knowledgesource.KnowledgeSourceDocumentsService;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseWorkspaceService {

    private final DataManager dataManager;
    private final KnowledgeSourceDocumentsService knowledgeSourceDocumentsService;

    public KnowledgeBaseWorkspaceService(DataManager dataManager,
                                         KnowledgeSourceDocumentsService knowledgeSourceDocumentsService) {
        this.dataManager = dataManager;
        this.knowledgeSourceDocumentsService = knowledgeSourceDocumentsService;
    }

    public List<KnowledgeSource> loadSources(UUID knowledgeBaseId) {
        return dataManager.load(KnowledgeSource.class)
                .query("select e from KnowledgeSource e where e.knowledgeBase.id = :knowledgeBaseId order by e.name")
                .parameter("knowledgeBaseId", knowledgeBaseId)
                .list();
    }

    public List<KnowledgeDocumentItem> loadDocuments(UUID knowledgeBaseId) {
        List<KnowledgeDocumentItem> documents = new ArrayList<>();
        for (KnowledgeSource source : loadSources(knowledgeBaseId)) {
            List<KnowledgeDocumentItem> sourceDocuments = knowledgeSourceDocumentsService.loadDocuments(source.getId());
            for (KnowledgeDocumentItem item : sourceDocuments) {
                item.setSourceName(source.getName());
                documents.add(item);
            }
        }
        documents.sort(Comparator
                .comparing(KnowledgeDocumentItem::getSourceName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(KnowledgeDocumentItem::getDocumentPath, Comparator.nullsLast(String::compareToIgnoreCase)));
        return documents;
    }

    public List<IngestionJob> loadJobs(UUID knowledgeBaseId) {
        return dataManager.load(IngestionJob.class)
                .query("select e from IngestionJob e where e.knowledgeBase.id = :knowledgeBaseId order by e.startedAt desc")
                .parameter("knowledgeBaseId", knowledgeBaseId)
                .list();
    }
}
