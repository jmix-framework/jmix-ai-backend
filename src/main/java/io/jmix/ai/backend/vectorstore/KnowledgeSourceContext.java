package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.entity.KnowledgeSource;

public record KnowledgeSourceContext(
        KnowledgeBase knowledgeBase,
        KnowledgeSource knowledgeSource
) {
}
