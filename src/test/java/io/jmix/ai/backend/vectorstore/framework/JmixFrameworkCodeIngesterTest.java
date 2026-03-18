package io.jmix.ai.backend.vectorstore.framework;

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

class JmixFrameworkCodeIngesterTest {

    @TempDir
    Path tempDir;

    @Test
    void selectsOnlyWhitelistedModulesAndMainSources() throws IOException {
        Files.writeString(tempDir.resolve("gradle.properties"), "version=1.7.999-SNAPSHOT");
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name='jmix'");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}");

        write(tempDir, "jmix-core/core/src/main/java/io/jmix/core/CoreClass.java", "package io.jmix.core;");
        write(tempDir, "jmix-data/data/src/main/resources/io/jmix/data/messages.properties", "test=value");
        write(tempDir, "jmix-ui/ui/build.gradle", "plugins {}");
        write(tempDir, "jmix-security/security-ui/src/main/java/io/jmix/securityui/SecurityView.java", "class SecurityView {}");
        write(tempDir, "jmix-core/core/src/test/java/io/jmix/core/CoreTest.java", "class CoreTest {}");
        write(tempDir, "jmix-email/email/src/main/java/io/jmix/email/EmailClass.java", "class EmailClass {}");

        TestableJmixFrameworkCodeIngester ingester = new TestableJmixFrameworkCodeIngester(tempDir);

        List<String> sources = ingester.exposedLoadSources();

        assertThat(sources).contains(
                "gradle.properties",
                "settings.gradle",
                "build.gradle",
                "jmix-core/core/src/main/java/io/jmix/core/CoreClass.java",
                "jmix-data/data/src/main/resources/io/jmix/data/messages.properties",
                "jmix-ui/ui/build.gradle",
                "jmix-security/security-ui/src/main/java/io/jmix/securityui/SecurityView.java"
        );
        assertThat(sources).doesNotContain(
                "jmix-core/core/src/test/java/io/jmix/core/CoreTest.java",
                "jmix-email/email/src/main/java/io/jmix/email/EmailClass.java"
        );
    }

    @Test
    void addsFrameworkMetadataToLoadedDocument() throws IOException {
        Files.createDirectories(tempDir.resolve("jmix-data"));
        Files.createDirectories(tempDir.resolve("jmix-ui"));
        Files.createDirectories(tempDir.resolve("jmix-security"));
        write(tempDir, "jmix-core/core/src/main/java/io/jmix/core/CoreClass.java", "package io.jmix.core;\nclass CoreClass {}");

        TestableJmixFrameworkCodeIngester ingester = new TestableJmixFrameworkCodeIngester(tempDir);
        ingester.prepareUpdate();

        Document document = ingester.loadDocument("jmix-core/core/src/main/java/io/jmix/core/CoreClass.java");

        assertThat(document.getText()).contains("Module: jmix-core", "Path: jmix-core/core/src/main/java/io/jmix/core/CoreClass.java");
        assertThat(document.getMetadata())
                .containsEntry("module", "jmix-core")
                .containsEntry("fileType", ".java")
                .containsEntry("docPath", "jmix-core/core/src/main/java/io/jmix/core/CoreClass.java")
                .containsEntry("url", "https://github.com/jmix-framework/jmix/tree/v1.7.2/jmix-core/core/src/main/java/io/jmix/core/CoreClass.java");
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static class TestableJmixFrameworkCodeIngester extends JmixFrameworkCodeIngester {

        private TestableJmixFrameworkCodeIngester(Path localPath) {
            super(
                    localPath.toString(),
                    0,
                    List.of("jmix-core", "jmix-data", "jmix-ui", "jmix-security"),
                    "https://github.com/jmix-framework/jmix/tree/v1.7.2",
                    128,
                    mock(VectorStore.class),
                    fixedTimeSource(),
                    emptyRepository(),
                    knowledgeSourceManager(localPath)
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
            knowledgeBase.setCode("kb-jmix-framework");

            KnowledgeSource knowledgeSource = new KnowledgeSource();
            knowledgeSource.setId(UUID.randomUUID());
            knowledgeSource.setKnowledgeBase(knowledgeBase);
            knowledgeSource.setCode("source-jmix-framework");
            knowledgeSource.setLocation(sourceLocation.toString());
            knowledgeSource.setLanguage("en");

            when(manager.resolve("jmix-framework-code")).thenReturn(new KnowledgeSourceContext(knowledgeBase, knowledgeSource));
            when(manager.startJob(any())).thenReturn(new IngestionJob());
            return manager;
        }

        private List<String> exposedLoadSources() {
            prepareUpdate();
            return loadSources();
        }
    }
}
