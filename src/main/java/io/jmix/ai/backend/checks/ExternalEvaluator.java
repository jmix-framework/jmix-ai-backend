package io.jmix.ai.backend.checks;

import org.springframework.lang.Nullable;

import java.util.function.Consumer;

public interface ExternalEvaluator {

    /**
     * Stable evaluator implementation and model settings used to identify analytical cohorts.
     */
    String configurationSnapshot();

    double evaluateSemantic(
            String question,
            String referenceAnswer,
            String actualAnswer,
            @Nullable Consumer<String> logger);
}
