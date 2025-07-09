package io.jmix.ai.backend.vectorstore.trainings;

import io.jmix.ai.backend.vectorstore.Chunker;

import java.util.List;

public class TrainingsChunker implements Chunker {

    private final int maxChunkSize;
    private final int minChunkSize;

    public TrainingsChunker(int maxChunkSize, int minChunkSize) {
        this.maxChunkSize = maxChunkSize;
        this.minChunkSize = minChunkSize;
    }

    @Override
    public List<Chunk> extract(String content, String docPath) {
        AsciidocPreprocessor preprocessor = new AsciidocPreprocessor();
        AsciidocBlockGrouper blockGrouper = new AsciidocBlockGrouper(maxChunkSize, minChunkSize);

        List<AsciidocBlock> blocks = preprocessor.extractBlocks(content);
        List<AsciidocBlock> groupedBlocks = blockGrouper.groupBlocks(blocks);

        List<Chunk> chunks = groupedBlocks.stream()
                .map(block ->
                        new Chunk(getTextWithHeader(block.text(), block.sectionPath()), null))
                .toList();

        return chunks;
    }

    private String getTextWithHeader(String text, String title) {
        return "# " + title + "\n\n" + text;
    }
}
