/**
 * Based on {@code ChunkGrouper} from https://github.com/m0mus/helidon-assistant
 */
package io.jmix.ai.backend.vectorstore.trainings;

import java.util.ArrayList;
import java.util.List;

public class AsciidocBlockGrouper {

    private final int maxChars;
    private final int minChars;

    public AsciidocBlockGrouper(int maxChars, int minChars) {
        this.maxChars = maxChars;
        this.minChars = minChars;
    }

    public List<AsciidocBlock> groupBlocks(List<AsciidocBlock> input) {
        List<AsciidocBlock> grouped = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        String currentSection = null;
        AsciidocBlock.Type type = AsciidocBlock.Type.MIXED;

        for (AsciidocBlock block : input) {
            if (currentSection == null) currentSection = block.sectionPath();

            // If switching section or block is too big to add
            if (!block.sectionPath().equals(currentSection) || sb.length() + block.text().length() > maxChars) {
                if (!sb.isEmpty() && sb.length() >= minChars) {
                    grouped.add(new AsciidocBlock(sb.toString().trim(), type, currentSection));
                    sb.setLength(0);
                }
                currentSection = block.sectionPath();
            }

            sb.append(block.text()).append("\n\n");
        }

        if (!sb.isEmpty() && sb.length() >= minChars) {
            grouped.add(new AsciidocBlock(sb.toString().trim(), type, currentSection));
        }

        return grouped;
    }
}
