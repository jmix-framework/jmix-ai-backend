package io.jmix.ai.backend.vectorstore;

import java.util.List;

public interface Chunker {

    record Chunk(String text, String title, String anchor) {
    }

    List<Chunk> extract(String content);
}
