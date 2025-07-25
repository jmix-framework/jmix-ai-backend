package io.jmix.ai.backend.checks;

import org.springframework.lang.Nullable;

import java.util.function.Consumer;

public interface ExternalEvaluator {

    enum Type { ROUGE, BERT }

    double evaluate(Type type, String referenceAnswer, String actualAnswer, @Nullable Consumer<String> logger);
}
