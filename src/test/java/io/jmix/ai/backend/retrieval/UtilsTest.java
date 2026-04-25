package io.jmix.ai.backend.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UtilsTest {

    @Test
    void getDistinctDocumentsCollapsesDocumentsWithSameDocumentPath() {
        Document first = new Document("1", "first", Map.of("documentPath", "security/resource-roles.html"));
        first.getMetadata().put("rerankScore", 0.9);
        Document second = new Document("2", "second", Map.of("documentPath", "security/resource-roles.html"));
        second.getMetadata().put("rerankScore", 0.8);

        List<Document> distinct = Utils.getDistinctDocuments(List.of(second, first));

        assertThat(distinct).containsExactly(first);
    }

    @Test
    void getDistinctDocumentsPreserveOrderCollapsesDocumentsWithSameUrlIgnoringFragment() {
        Document first = new Document("1", "first", Map.of("url", "https://docs.jmix.ru/1.x/jmix/1.7/security/resource-roles.html#example"));
        Document second = new Document("2", "second", Map.of("url", "https://docs.jmix.ru/1.x/jmix/1.7/security/resource-roles.html#create"));

        List<Document> distinct = Utils.getDistinctDocumentsPreserveOrder(List.of(first, second));

        assertThat(distinct).containsExactly(first);
    }
}
