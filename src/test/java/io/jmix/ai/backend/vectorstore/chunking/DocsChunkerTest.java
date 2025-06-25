package io.jmix.ai.backend.vectorstore.chunking;

import io.jmix.ai.backend.vectorstore.Chunker;
import io.jmix.ai.backend.vectorstore.docs.DocsChunker;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class DocsChunkerTest {

    @Test
    void testComplexDocument() throws Exception{
        DocsChunker extractor = new DocsChunker(1000, 10, 10);
        List<Chunker.Chunk> chunks = extractor.extract(loadResourceAsString("/test_support/sources/doc-1.html"));

        assertThat(chunks).size().isEqualTo(6);

        assertThat(chunks.get(0).text()).startsWith("# Web Development Fundamentals\nWeb development encompasses").endsWith("modern web development.");
        assertThat(chunks.get(0).title()).isEqualTo("Web Development Fundamentals");
        assertThat(chunks.get(0).anchor()).isNull();

        assertThat(chunks.get(1).text()).startsWith("# Web Development Fundamentals. Frontend Development\nFrontend development focuses").endsWith("more intuitive and powerful.");
        assertThat(chunks.get(1).title()).isEqualTo("Web Development Fundamentals. Frontend Development");
        assertThat(chunks.get(1).anchor()).isEqualTo("#frontend");

        assertThat(chunks.get(2).text()).startsWith("# Web Development Fundamentals. Backend Development\nBackend development involves").endsWith("across various user scenarios.");
        assertThat(chunks.get(2).title()).isEqualTo("Web Development Fundamentals. Backend Development");
        assertThat(chunks.get(2).anchor()).isEqualTo("#backend");

        assertThat(chunks.get(3).text()).startsWith("# Web Development Fundamentals. Backend Development. Server Technologies\nServer technologies include").endsWith("and updates are essential.");
        assertThat(chunks.get(3).title()).isEqualTo("Web Development Fundamentals. Backend Development. Server Technologies");
        assertThat(chunks.get(3).anchor()).isEqualTo("#server-tech");

        assertThat(chunks.get(4).text()).startsWith("# Web Development Fundamentals. Backend Development. Database Systems\nDatabases store and organize").endsWith("and query patterns.");
        assertThat(chunks.get(4).title()).isEqualTo("Web Development Fundamentals. Backend Development. Database Systems");
        assertThat(chunks.get(4).anchor()).isEqualTo("#db");
    }

    @Test
    void testSkipTooShortDocPreamble() throws Exception {
        DocsChunker extractor = new DocsChunker(1000, 500, 10);
        List<Chunker.Chunk> chunks = extractor.extract(loadResourceAsString("/test_support/sources/doc-1.html"));

        assertThat(chunks).size().isEqualTo(5);

        assertThat(chunks.get(0).text()).startsWith("# Web Development Fundamentals. Frontend Development\nFrontend development focuses").endsWith("more intuitive and powerful.");
        assertThat(chunks.get(0).title()).isEqualTo("Web Development Fundamentals. Frontend Development");
        assertThat(chunks.get(0).anchor()).isEqualTo("#frontend");
    }

    @Test
    void testSkipTooShortSect1Preamble() throws Exception {
        DocsChunker extractor = new DocsChunker(1000, 10, 500);
        List<Chunker.Chunk> chunks = extractor.extract(loadResourceAsString("/test_support/sources/doc-1.html"));

        assertThat(chunks).size().isEqualTo(5);

        assertThat(chunks.get(2).text()).startsWith("# Web Development Fundamentals. Backend Development. Server Technologies\nServer technologies include").endsWith("and updates are essential.");
        assertThat(chunks.get(2).title()).isEqualTo("Web Development Fundamentals. Backend Development. Server Technologies");
        assertThat(chunks.get(2).anchor()).isEqualTo("#server-tech");
    }

    @Test
    void testDocWithoutSections() throws Exception {
        DocsChunker extractor = new DocsChunker(1000, 10, 10);
        List<Chunker.Chunk> chunks = extractor.extract(loadResourceAsString("/test_support/sources/doc-2.html"));

        assertThat(chunks).size().isEqualTo(1);

        assertThat(chunks.get(0).text()).startsWith("# Web Development Fundamentals\nFrontend development focuses").endsWith("more manageable.");
        assertThat(chunks.get(0).title()).isEqualTo("Web Development Fundamentals");
        assertThat(chunks.get(0).anchor()).isNull();
    }

    @Test
    void testSkipTooShortDocWithoutSections() throws Exception {
        DocsChunker extractor = new DocsChunker(1000, 500, 10);
        List<Chunker.Chunk> chunks = extractor.extract(loadResourceAsString("/test_support/sources/doc-2.html"));

        assertThat(chunks).isEmpty();
    }

    private String loadResourceAsString(String path) throws IOException {
        return IOUtils.resourceToString(path, StandardCharsets.UTF_8);
    }
}
