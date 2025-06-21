package io.jmix.ai.backend.vectorstore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IngesterIntegrationTest {

    @Autowired
    private IngesterManager ingesterManager;

    @Test
    void shouldReturnAvailableIngesterTypes() {
        List<String> types = ingesterManager.getTypes();
        assertThat(types).containsAll(List.of("docs", "uisamples", "trainings"));
    }
}
