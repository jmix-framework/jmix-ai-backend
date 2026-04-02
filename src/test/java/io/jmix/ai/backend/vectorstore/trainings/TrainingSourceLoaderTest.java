package io.jmix.ai.backend.vectorstore.trainings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingSourceLoaderTest {

    private final TrainingSourceLoader loader = new TrainingSourceLoader();

    @TempDir
    Path tempDir;

    @Test
    void expandsIncludesRecursively() throws IOException {
        Path courseDir = Files.createDirectories(tempDir.resolve("course/parts_RU"));
        Files.writeString(tempDir.resolve("asciidoctorconfig.adoc"), ":experimental:\n");
        Files.writeString(courseDir.resolve("part_1.adoc"), "== Часть 1\n\nТекст части 1.");
        Files.writeString(courseDir.resolve("part_2.adoc"), "== Часть 2\n\nТекст части 2.");

        Path root = tempDir.resolve("course/training_RU.adoc");
        Files.writeString(root, """
                include::../asciidoctorconfig.adoc[]

                = Курс

                include::parts_RU/part_1.adoc[]
                include::parts_RU/part_2.adoc[]
                """);

        String content = loader.load(root);

        assertThat(content)
                .contains(":experimental:")
                .contains("= Курс")
                .contains("== Часть 1")
                .contains("Текст части 1.")
                .contains("== Часть 2")
                .contains("Текст части 2.");
    }

    @Test
    void failsOnMissingInclude() throws IOException {
        Path root = tempDir.resolve("training_RU.adoc");
        Files.writeString(root, "include::parts_RU/missing.adoc[]\n");

        assertThatThrownBy(() -> loader.load(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Included training file not found");
    }
}
