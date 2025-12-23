package io.jmix.ai.backend.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

import org.springframework.lang.Nullable;


public enum ParametersTargetType implements EnumClass<String> {

    CHAT("CHAT"),
    SEARCH("SEARCH");

    private final String id;

    ParametersTargetType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static ParametersTargetType fromId(String id) {
        for (ParametersTargetType at : ParametersTargetType.values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}