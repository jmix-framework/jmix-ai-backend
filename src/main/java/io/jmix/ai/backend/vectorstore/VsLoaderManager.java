package io.jmix.ai.backend.vectorstore;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VsLoaderManager {

    private final List<VsLoader> loaders;

    public VsLoaderManager(List<VsLoader> loaders) {
        this.loaders = loaders;
    }

    public void load() {
        for (VsLoader loader : loaders) {
            loader.load();
        }
    }
}
