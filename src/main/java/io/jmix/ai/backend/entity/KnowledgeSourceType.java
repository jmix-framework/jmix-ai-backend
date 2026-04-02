package io.jmix.ai.backend.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

public enum KnowledgeSourceType implements EnumClass<String> {

    DOCS_SITE("DOCS_SITE"),
    LOCAL_REPOSITORY("LOCAL_REPOSITORY"),
    GIT_REPOSITORY("GIT_REPOSITORY"),
    LOCAL_DIRECTORY("LOCAL_DIRECTORY"),
    UPLOAD("UPLOAD");

    private final String id;

    KnowledgeSourceType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static KnowledgeSourceType fromId(String id) {
        for (KnowledgeSourceType value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
