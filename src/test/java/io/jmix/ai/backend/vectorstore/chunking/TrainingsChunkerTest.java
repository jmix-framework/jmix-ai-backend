package io.jmix.ai.backend.vectorstore.chunking;

import io.jmix.ai.backend.vectorstore.trainings.TrainingsChunker;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TrainingsChunkerTest {

    @Test
    void test() throws IOException {
        TrainingsChunker chunker = new TrainingsChunker(5_000, 200);
        List<Chunk> chunks = chunker.extract(IOUtils.resourceToString("/test_support/sources/training-1.adoc", StandardCharsets.UTF_8));

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            System.out.println("Chunk #: " + i + " " + chunk.title() + " " + chunk.text().length());
        }

        assertThat(chunks).size ().isEqualTo (7);

        assertThat(chunks.get(0).text()).startsWith("# Views Practice. List view\n\nLet's start our practice").endsWith("Compare with **Time Entries list view** to prove");
        assertThat(chunks.get(0).title()).isEqualTo("Views Practice. List view");

        assertThat(chunks.get(1).text()).startsWith("# Views Practice. List view\n\nLet's develop your view further").endsWith("it's a topic of the next section.");
        assertThat(chunks.get(1).title()).isEqualTo("Views Practice. List view");

        assertThat(chunks.get(2).text()).startsWith("# Views Practice. List view\n\nBefore we proceed").endsWith("with UI development.");
        assertThat(chunks.get(2).title()).isEqualTo("Views Practice. List view");

        assertThat(chunks.get(3).text()).startsWith("# Views Practice. Lookup view\n\nNow, let's talk about lookup views").endsWith("Show changes in the app");
        assertThat(chunks.get(3).title()).isEqualTo("Views Practice. Lookup view");

        assertThat(chunks.get(6).text()).startsWith("# Views Practice. Detail view. ClientDetailView\n\nThe next view that").endsWith("and show changes");
        assertThat(chunks.get(6).title()).isEqualTo("Views Practice. Detail view. ClientDetailView");
    }
}
