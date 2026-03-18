package io.jmix.ai.backend.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

public enum IngestionJobStatus implements EnumClass<String> {

    NEW("NEW"),
    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED");

    private final String id;

    IngestionJobStatus(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static IngestionJobStatus fromId(String id) {
        for (IngestionJobStatus value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
