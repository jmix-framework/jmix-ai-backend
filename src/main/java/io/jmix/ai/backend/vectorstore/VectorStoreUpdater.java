package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;

public interface VectorStoreUpdater {

    String getType();

    String updateAll();

    String update(VectorStoreEntity entity);
}
