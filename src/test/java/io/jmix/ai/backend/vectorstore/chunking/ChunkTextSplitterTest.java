package io.jmix.ai.backend.vectorstore.chunking;

import io.jmix.ai.backend.vectorstore.ChunkTextSplitter;
import io.jmix.ai.backend.vectorstore.Chunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkTextSplitterTest {

    @Test
    void shouldKeepShortChunkAsIs() {
        ChunkTextSplitter splitter = new ChunkTextSplitter(50, 10);

        List<Chunker.Chunk> chunks = splitter.split(new Chunker.Chunk("Path: Test\n\nshort text", "#a"));

        assertThat(chunks).containsExactly(new Chunker.Chunk("Path: Test\n\nshort text", "#a"));
    }

    @Test
    void shouldSplitLongChunkAndPreserveHeader() {
        ChunkTextSplitter splitter = new ChunkTextSplitter(80, 10);
        String body = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen";

        List<Chunker.Chunk> chunks = splitter.split(new Chunker.Chunk("Path: Test\n\n" + body, "#a"));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text()).startsWith("Path: Test\n\n");
            assertThat(chunk.text().length()).isLessThanOrEqualTo(80);
            assertThat(chunk.anchor()).isEqualTo("#a");
        });
    }

    @Test
    void shouldSplitLongPlainTextWithoutHeader() {
        ChunkTextSplitter splitter = new ChunkTextSplitter(40, 5);
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu";

        List<Chunker.Chunk> chunks = splitter.split(new Chunker.Chunk(text, null));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(40));
    }
}
