package io.jmix.ai.backend.vectorstore;

public interface VectorStoreUpdater {

    String getType();

    String update();
}
