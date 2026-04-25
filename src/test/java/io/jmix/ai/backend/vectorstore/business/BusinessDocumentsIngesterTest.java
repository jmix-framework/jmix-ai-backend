package io.jmix.ai.backend.vectorstore.business;

import io.jmix.ai.backend.entity.IngestionJob;
import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.vectorstore.KnowledgeSourceContext;
import io.jmix.ai.backend.vectorstore.KnowledgeSourceManager;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessDocumentsIngesterTest {

    @TempDir
    Path tempDir;

    @Test
    void selectsOnlySupportedTextFiles() throws IOException {
        write(tempDir, "orders/client-a/order-001.txt", "Order 001");
        write(tempDir, "orders/client-a/order-002.md", "# Order 002");
        write(tempDir, "orders/client-a/order-003.pdf", "%PDF-1.7");
        write(tempDir, ".git/ignored.txt", "ignore");

        TestableBusinessDocumentsIngester ingester = new TestableBusinessDocumentsIngester(tempDir, null);

        List<String> sources = ingester.exposedLoadSources();

        assertThat(sources)
                .containsExactly(
                        "orders/client-a/order-001.txt",
                        "orders/client-a/order-002.md"
                );
    }

    @Test
    void usesKnowledgeSourceLocationOverrideAndAddsBusinessMetadata() throws IOException {
        Path fallbackDir = Files.createDirectories(tempDir.resolve("fallback"));
        Path effectiveDir = Files.createDirectories(tempDir.resolve("effective/contracts"));
        write(fallbackDir, "ignored.txt", "Ignore me");
        write(effectiveDir.getParent(), "contracts/client-a-summary.csv", "client,total\nA,1500");

        TestableBusinessDocumentsIngester ingester = new TestableBusinessDocumentsIngester(fallbackDir, effectiveDir.getParent());
        ingester.prepareUpdate();

        Document document = ingester.loadDocument("contracts/client-a-summary.csv");

        assertThat(document.getText())
                .contains("Business document path: contracts/client-a-summary.csv")
                .contains("client,total");
        assertThat(document.getMetadata())
                .containsEntry("type", "business-documents")
                .containsEntry("documentPath", "contracts/client-a-summary.csv")
                .containsEntry("documentName", "client-a-summary.csv")
                .containsEntry("documentKind", "document")
                .containsEntry("fileType", ".csv")
                .containsEntry("sourceCode", "business-documents-source");
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static class TestableBusinessDocumentsIngester extends BusinessDocumentsIngester {

        private TestableBusinessDocumentsIngester(Path localPath, Path sourceLocation) {
            super(
                    localPath.toString(),
                    0,
                    128,
                    mock(VectorStore.class),
                    fixedTimeSource(),
                    emptyRepository(),
                    knowledgeSourceManager(sourceLocation)
            );
        }

        private static TimeSource fixedTimeSource() {
            TimeSource timeSource = mock(TimeSource.class);
            when(timeSource.currentTimeMillis()).thenReturn(Instant.now().toEpochMilli());
            when(timeSource.now()).thenReturn(ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()));
            return timeSource;
        }

        private static VectorStoreRepository emptyRepository() {
            VectorStoreRepository repository = mock(VectorStoreRepository.class);
            when(repository.loadList(anyString())).thenReturn(List.of());
            return repository;
        }

        private static KnowledgeSourceManager knowledgeSourceManager(Path sourceLocation) {
            KnowledgeSourceManager manager = mock(KnowledgeSourceManager.class);
            KnowledgeBase knowledgeBase = new KnowledgeBase();
            knowledgeBase.setCode("business-documents-demo");

            KnowledgeSource knowledgeSource = new KnowledgeSource();
            knowledgeSource.setId(UUID.randomUUID());
            knowledgeSource.setKnowledgeBase(knowledgeBase);
            knowledgeSource.setCode("business-documents-source");
            knowledgeSource.setLocation(sourceLocation != null ? sourceLocation.toString() : null);
            knowledgeSource.setLanguage("ru");

            when(manager.resolve("business-documents")).thenReturn(new KnowledgeSourceContext(knowledgeBase, knowledgeSource));
            when(manager.startJob(any())).thenReturn(new IngestionJob());
            return manager;
        }

        private List<String> exposedLoadSources() {
            prepareUpdate();
            return loadSources();
        }
    }
}
