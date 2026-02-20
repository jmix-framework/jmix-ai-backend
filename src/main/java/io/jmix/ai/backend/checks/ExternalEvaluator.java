package io.jmix.ai.backend.checks;

import org.springframework.lang.Nullable;

import java.util.function.Consumer;

public interface ExternalEvaluator {

    double evaluateSemantic(String referenceAnswer, String actualAnswer, @Nullable Consumer<String> logger);
}
