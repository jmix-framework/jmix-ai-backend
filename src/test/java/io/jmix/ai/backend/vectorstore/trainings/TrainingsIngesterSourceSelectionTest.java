package io.jmix.ai.backend.vectorstore.trainings;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainingsIngesterSourceSelectionTest {

    @TempDir
    Path tempDir;

    @Test
    void selectsOnlyRootRuCourseFiles() throws IOException {
        Path basicCourse = Files.createDirectories(tempDir.resolve("1 Basic courses/1. Intro"));
        Files.createDirectories(tempDir.resolve("2 Advanced courses"));
        Files.createDirectories(tempDir.resolve("3 Additional courses"));
        Path partsRu = Files.createDirectories(basicCourse.resolve("parts_RU"));
        Path partsEn = Files.createDirectories(basicCourse.resolve("parts_EN"));
        Files.createDirectories(tempDir.resolve("Other/Test questions (Basic)"));

        Files.writeString(basicCourse.resolve("intro_RU.adoc"), "= RU course");
        Files.writeString(basicCourse.resolve("intro_EN.adoc"), "= EN course");
        Files.writeString(partsRu.resolve("part_1.adoc"), "RU part");
        Files.writeString(partsEn.resolve("part_1.adoc"), "EN part");
        Files.writeString(tempDir.resolve("Other/Test questions (Basic)/test_questions_RU-draft.adoc"), "= Draft");

        TestableTrainingsIngester ingester = new TestableTrainingsIngester(tempDir);

        List<String> sources = ingester.exposedLoadSources();

        assertThat(sources)
                .containsExactly("1 Basic courses/1. Intro/intro_RU.adoc");
    }

    @Test
    void usesKnowledgeSourceLocationAndLanguageOverrides() throws IOException {
        Path fallbackDir = Files.createDirectories(tempDir.resolve("fallback"));
        Path effectiveDir = Files.createDirectories(tempDir.resolve("effective/1 Basic courses/1. Intro"));
        Files.createDirectories(tempDir.resolve("effective/2 Advanced courses"));
        Files.createDirectories(tempDir.resolve("effective/3 Additional courses"));

        Files.writeString(fallbackDir.resolve("ignored_EN.adoc"), "= Fallback");
        Files.writeString(effectiveDir.resolve("intro_EN.adoc"), "= EN course");
        Files.writeString(effectiveDir.resolve("intro_RU.adoc"), "= RU course");

        TestableTrainingsIngester ingester = new TestableTrainingsIngester(
                fallbackDir,
                tempDir.resolve("effective"),
                "EN"
        );

        List<String> sources = ingester.exposedLoadSources();

        assertThat(sources)
                .containsExactly("1 Basic courses/1. Intro/intro_EN.adoc");
    }

    private static class TestableTrainingsIngester extends TrainingsIngester {

        private TestableTrainingsIngester(Path localPath) {
            this(localPath, null, null);
        }

        private TestableTrainingsIngester(Path localPath, Path sourceLocation, String sourceLanguage) {
            super(
                    localPath.toString(),
                    0,
                    List.of("1 Basic courses", "2 Advanced courses", "3 Additional courses"),
                    List.of("none"),
                    "RU",
                    128,
                    mock(VectorStore.class),
                    fixedTimeSource(),
                    emptyRepository(),
                    knowledgeSourceManager(sourceLocation, sourceLanguage)
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

        private static KnowledgeSourceManager knowledgeSourceManager(Path sourceLocation, String sourceLanguage) {
            KnowledgeSourceManager manager = mock(KnowledgeSourceManager.class);
            KnowledgeBase knowledgeBase = new KnowledgeBase();
            knowledgeBase.setCode("kb-trainings");

            KnowledgeSource knowledgeSource = new KnowledgeSource();
            knowledgeSource.setKnowledgeBase(knowledgeBase);
            knowledgeSource.setCode("source-trainings");
            knowledgeSource.setLocation(sourceLocation != null ? sourceLocation.toString() : null);
            knowledgeSource.setLanguage(sourceLanguage);

            when(manager.resolve("trainings")).thenReturn(new KnowledgeSourceContext(knowledgeBase, knowledgeSource));
            when(manager.startJob(org.mockito.ArgumentMatchers.any())).thenReturn(new IngestionJob());
            return manager;
        }

        private List<String> exposedLoadSources() {
            prepareUpdate();
            return loadSources();
        }

        @Override
        protected Document loadDocument(String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected List<Document> splitToChunks(List<Document> documents) {
            throw new UnsupportedOperationException();
        }
    }
}
