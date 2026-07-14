package io.jmix.ai.backend.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UtilsTest {

    @Test
    void returnsUniqueDocumentsSortedByRelevance() {
        Document lowerRankedDuplicate = Document.builder()
                .id("duplicate")
                .text("lower-ranked duplicate")
                .score(0.1)
                .build();
        Document similarityRanked = Document.builder()
                .id("similarity")
                .text("similarity-ranked")
                .score(0.8)
                .build();
        Document higherRankedDuplicate = Document.builder()
                .id("duplicate")
                .text("higher-ranked duplicate")
                .metadata(Map.of("rerankScore", 0.9))
                .score(0.2)
                .build();

        List<Document> result = Utils.getUniqueSortedDocuments(
                List.of(lowerRankedDuplicate, similarityRanked, higherRankedDuplicate));

        assertThat(result).extracting(Document::getId)
                .containsExactly("duplicate", "similarity");
        assertThat(result.getFirst().getText()).isEqualTo("higher-ranked duplicate");
    }
}
