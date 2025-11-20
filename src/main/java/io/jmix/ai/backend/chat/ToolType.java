package io.jmix.ai.backend.chat;

import io.jmix.core.metamodel.datatype.EnumClass;

import org.springframework.lang.Nullable;


public enum ToolType implements EnumClass<String> {
    DOCS("docs"),
    TRAININGS("trainings"),
    UI_SAMPLES("uisamples");
    ;

    private final String id;

    ToolType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static ToolType fromId(String id) {
        for (ToolType at : ToolType.values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}