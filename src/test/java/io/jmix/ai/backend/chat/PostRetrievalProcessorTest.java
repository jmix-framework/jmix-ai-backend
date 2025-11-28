package io.jmix.ai.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.retrieval.PostRetrievalProcessor;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PostRetrievalProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void testTabbedModeRule() {
        PostRetrievalProcessor processor = applicationContext.getBean(PostRetrievalProcessor.class, getParametersReader(), null);

        Document d1 = new Document("1", "content 1", Map.of("source", "test1"));
        Document d2 = new Document("2", "Path: Add-ons > Tabbed Application Mode > Opening Views\n\nYou can open views", Map.of("source", "test2"));

        List<Document> processed = processor.process("test", List.of(d1, d2));
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).getId()).isEqualTo("1");

        processed = processor.process("test tabbed", List.of(d1, d2));
        assertThat(processed).hasSize(2);
        assertThat(processed.get(1).getId()).isEqualTo("2");
    }

    @Test
    void testBpmRule() {
        PostRetrievalProcessor processor = applicationContext.getBean(PostRetrievalProcessor.class, getParametersReader(), null);

        Document d1 = new Document("1", "content 1", Map.of("source", "test1"));
        Document d2 = new Document("2", "Path: Add-ons > BPM > Using BPMN 2.0 > BPMN 2.0 Elements > Tasks > Script Task\n\nOverview A script task", Map.of("source", "test2"));

        List<Document> processed = processor.process("test", List.of(d1, d2));
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).getId()).isEqualTo("1");

        processed = processor.process("test bpmn", List.of(d1, d2));
        assertThat(processed).hasSize(2);
        assertThat(processed.get(1).getId()).isEqualTo("2");
    }

    private ParametersReader getParametersReader() {
        return new ParametersReader(getObjectMap());
    }

    private Map<String, Object> getObjectMap() {
        try {
            InputStream inputStream = getClass().getResourceAsStream("/test_support/params/params1.yaml");
            if (inputStream == null) {
                throw new IllegalArgumentException("Resource not found");
            }
            String resourceContent = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            //noinspection unchecked
            Map<String, Object> resourceData = objectMapper.readValue(resourceContent, Map.class);
            return resourceData;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}