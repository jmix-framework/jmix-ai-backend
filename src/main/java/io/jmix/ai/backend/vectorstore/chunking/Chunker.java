package io.jmix.ai.backend.vectorstore.chunking;

import java.util.List;

public interface Chunker {

    List<Chunk> extract(String content);
}
