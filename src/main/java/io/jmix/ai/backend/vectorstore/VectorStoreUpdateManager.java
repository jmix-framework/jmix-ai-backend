package io.jmix.ai.backend.vectorstore;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VectorStoreUpdateManager {

    private final List<VectorStoreUpdater> updaters;

    public VectorStoreUpdateManager(List<VectorStoreUpdater> updaters) {
        this.updaters = updaters;
    }

    public String update() {
        StringBuilder sb = new StringBuilder();
        for (VectorStoreUpdater updater : updaters) {
            sb.append("<b>").append(updater.getType()).append("</b><br>");

            String result = updater.update();

            sb.append(result);
        }
        return sb.toString();
    }
}
