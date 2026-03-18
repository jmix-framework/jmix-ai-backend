package io.jmix.ai.backend.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

public enum KnowledgeSourceUpdateMode implements EnumClass<String> {

    ON_DEMAND("ON_DEMAND"),
    MANUAL("MANUAL");

    private final String id;

    KnowledgeSourceUpdateMode(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static KnowledgeSourceUpdateMode fromId(String id) {
        for (KnowledgeSourceUpdateMode value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
