package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RetrieverManager {

    private final List<Retriever> retrievers;

    public RetrieverManager(List<Retriever> retrievers) {
        this.retrievers = retrievers;
    }

    public String update() {
        StringBuilder sb = new StringBuilder();
        for (Retriever updater : retrievers) {
            String result = updater.updateAll();
            sb.append("<b>").append(updater.getType()).append("</b><br>").append(result).append("<br>");
        }
        return sb.toString();
    }

    public List<String> getTypes() {
        return retrievers.stream()
                .map(Retriever::getType)
                .toList();
    }

    public String updateByType(String type) {
        StringBuilder sb = new StringBuilder();
        retrievers.stream()
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
        retrievers.stream()
                .filter(updater -> updater.getType().equals(type))
                .findFirst()
                .ifPresent(updater -> {
                    String result = updater.update(entity);
                    sb.append("<b>").append(updater.getType()).append("</b><br>").append(result);
                });
        return sb.toString();
    }
}
