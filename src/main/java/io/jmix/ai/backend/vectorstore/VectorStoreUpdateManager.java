package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;
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
            String result = updater.updateAll();
            sb.append("<b>").append(updater.getType()).append("</b><br>").append(result);
        }
        return sb.toString();
    }

    public List<String> getTypes() {
        return updaters.stream()
                .map(VectorStoreUpdater::getType)
                .toList();
    }

    public String updateByType(String type) {
        StringBuilder sb = new StringBuilder();
        updaters.stream()
                .filter(updater -> updater.getType().equals(type))
                .findFirst()
                .ifPresent(updater -> {
                    String result = updater.updateAll();
                    sb.append("<b>").append(updater.getType()).append("</b><br>").append(result);
                });
        return sb.toString();
    }

    public String updateByEntity(VectorStoreEntity entity) {
        StringBuilder sb = new StringBuilder();
        String type = (String) entity.getMetadataMap().get("type");
        updaters.stream()
                .filter(updater -> updater.getType().equals(type))
                .findFirst()
                .ifPresent(updater -> {
                    String result = updater.update(entity);
                    sb.append("<b>").append(updater.getType()).append("</b><br>").append(result);
                });
        return sb.toString();
    }
}
