package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.VectorStoreEntity;

import java.util.List;

public interface Ingester {

    String getType();

    List<JmixVersion> getVersions();

    String updateAll();

    String updateAll(JmixVersion version);

    String update(VectorStoreEntity entity);
}
