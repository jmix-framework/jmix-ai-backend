package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.entity.KnowledgeSourceType;
import io.jmix.core.DataManager;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class KnowledgeDocumentPreviewService {

    private final DataManager dataManager;

    public KnowledgeDocumentPreviewService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public PreviewedDocument loadDocument(String sourceId, String kbCode, String sourceCode, String documentPath) {
        KnowledgeSource source = resolveSource(sourceId, kbCode, sourceCode);
        Path resolvedPath = resolvePath(source, documentPath);
        try {
            return new PreviewedDocument(
                    source.getName(),
                    documentPath,
                    Files.readString(resolvedPath)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read knowledge document: " + documentPath, e);
        }
    }

    KnowledgeSource resolveSource(String sourceId, String kbCode, String sourceCode) {
        KnowledgeSource source;
        if (StringUtils.isNotBlank(sourceId)) {
            source = dataManager.load(KnowledgeSource.class)
                    .id(UUID.fromString(sourceId))
                    .optional()
                    .orElse(null);
        } else if (StringUtils.isNotBlank(kbCode) && StringUtils.isNotBlank(sourceCode)) {
            source = dataManager.load(KnowledgeSource.class)
                    .query("select e from KnowledgeSource e where e.knowledgeBase.code = ?1 and e.code = ?2", kbCode, sourceCode)
                    .optional()
                    .orElse(null);
        } else {
            source = null;
        }

        if (source == null) {
            throw new IllegalStateException("Knowledge source not found");
        }
        if (source.getSourceType() != KnowledgeSourceType.LOCAL_DIRECTORY
                && source.getSourceType() != KnowledgeSourceType.LOCAL_REPOSITORY
                && source.getSourceType() != KnowledgeSourceType.UPLOAD) {
            throw new IllegalArgumentException("Knowledge source type does not support local preview");
        }
        if (StringUtils.isBlank(source.getLocation())) {
            throw new IllegalStateException("Knowledge source location is not configured");
        }
        return source;
    }

    Path resolvePath(KnowledgeSource source, String documentPath) {
        if (StringUtils.isBlank(documentPath)) {
            throw new IllegalArgumentException("Document path is required");
        }
        Path rootPath = Path.of(source.getLocation()).normalize().toAbsolutePath();
        Path resolvedPath = rootPath.resolve(documentPath).normalize().toAbsolutePath();
        if (!resolvedPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("Document path points outside of the configured source root");
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IllegalStateException("Knowledge document not found: " + documentPath);
        }
        return resolvedPath;
    }

    public record PreviewedDocument(String sourceName, String documentPath, String content) {
    }
}
