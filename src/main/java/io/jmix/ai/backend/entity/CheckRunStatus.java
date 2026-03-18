package io.jmix.ai.backend.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

public enum CheckRunStatus implements EnumClass<String> {
    NEW("NEW"),
    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED");

    private final String id;

    CheckRunStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static CheckRunStatus fromId(String id) {
        for (CheckRunStatus value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
