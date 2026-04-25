package io.jmix.ai.backend.vectorstore.business;

import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.entity.KnowledgeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDocumentsUploadServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void upload_savesFileIntoUploadsDirectory() throws Exception {
        BusinessDocumentsUploadService service = new BusinessDocumentsUploadService();
        KnowledgeSource source = businessSource(tempDir);

        String storedPath = service.upload(source, "orders.md", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(storedPath).isEqualTo("uploads/orders.md");
        assertThat(Files.readString(tempDir.resolve("uploads/orders.md"))).isEqualTo("hello");
    }

    @Test
    void upload_rejectsUnsupportedExtension() {
        BusinessDocumentsUploadService service = new BusinessDocumentsUploadService();
        KnowledgeSource source = businessSource(tempDir);

        assertThatThrownBy(() -> service.upload(source, "orders.pdf", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
    }

    private KnowledgeSource businessSource(Path location) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setCode(BusinessDocumentsSupport.KNOWLEDGE_BASE_CODE);

        KnowledgeSource source = new KnowledgeSource();
        source.setKnowledgeBase(knowledgeBase);
        source.setCode(BusinessDocumentsSupport.KNOWLEDGE_SOURCE_CODE);
        source.setLocation(location.toString());
        return source;
    }
}
