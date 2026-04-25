package io.jmix.ai.backend.chat;

import org.apache.commons.lang3.StringUtils;

enum ChatMode {
    ALWAYS_RAG("always_rag", true, false),
    CLASSIFIER_RAG("classifier_rag", false, true),
    NARROW_RAG("narrow_rag", true, false);

    private final String id;
    private final boolean alwaysEnableTools;
    private final boolean classifierPrefetch;

    ChatMode(String id, boolean alwaysEnableTools, boolean classifierPrefetch) {
        this.id = id;
        this.alwaysEnableTools = alwaysEnableTools;
        this.classifierPrefetch = classifierPrefetch;
    }

    String getId() {
        return id;
    }

    boolean alwaysEnableTools() {
        return alwaysEnableTools;
    }

    boolean classifierPrefetch() {
        return classifierPrefetch;
    }

    static ChatMode fromId(String id) {
        if (StringUtils.isBlank(id)) {
            return CLASSIFIER_RAG;
        }
        for (ChatMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id.trim())) {
                return mode;
            }
        }
        return CLASSIFIER_RAG;
    }
}
