package io.jmix.ai.backend.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

import org.springframework.lang.Nullable;


public enum JmixVersion implements EnumClass<String> {

    V2("v2"),
    V3("v3");

    private final String id;

    JmixVersion(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static JmixVersion fromId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (JmixVersion v : JmixVersion.values()) {
            if (v.getId().equalsIgnoreCase(id.trim())) {
                return v;
            }
        }
        return null;
    }
}
