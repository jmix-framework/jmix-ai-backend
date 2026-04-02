package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IngesterManager {

    private final List<Ingester> ingesters;
    private final KnowledgeSourceManager knowledgeSourceManager;

    public IngesterManager(List<Ingester> ingesters, KnowledgeSourceManager knowledgeSourceManager) {
        this.ingesters = ingesters;
        this.knowledgeSourceManager = knowledgeSourceManager;
    }

    public String update() {
        StringBuilder sb = new StringBuilder();
        for (ManagedSource managedSource : getSources()) {
            Ingester updater = managedSource.ingester();
            String result = updater.updateAll();
            sb.append("<b>").append(managedSource.name()).append("</b><br>").append(result).append("<br>");
        }
        return sb.toString();
    }

    public List<String> getTypes() {
        return ingesters.stream()
                .map(Ingester::getType)
                .toList();
    }

    public List<ManagedSource> getSources() {
        return ingesters.stream()
                .filter(ingester -> knowledgeSourceManager.isEnabled(ingester.getType()))
                .map(ingester -> {
                    KnowledgeSourceContext context = knowledgeSourceManager.resolve(ingester.getType());
                    return new ManagedSource(
                            context.knowledgeSource().getCode(),
                            context.knowledgeSource().getName(),
                            ingester.getType(),
                            ingester
                    );
                })
                .toList();
    }

    public String updateByType(String type) {
        StringBuilder sb = new StringBuilder();
        ingesters.stream()
                .filter(updater -> updater.getType().equals(type))
                .findFirst()
                .ifPresent(updater -> {
                    String result = updater.updateAll();
                    sb.append("<b>").append(updater.getType()).append("</b><br>").append(result);
                });
        return sb.toString();
    }

    public String updateBySourceCode(String sourceCode) {
        StringBuilder sb = new StringBuilder();
        getSources().stream()
                .filter(source -> source.code().equals(sourceCode))
                .findFirst()
                .ifPresent(source -> {
                    String result = source.ingester().updateAll();
                    sb.append("<b>").append(source.name()).append("</b><br>").append(result);
                });
        return sb.toString();
    }

    public String updateByEntity(VectorStoreEntity entity) {
        StringBuilder sb = new StringBuilder();
        String sourceCode = (String) entity.getMetadataMap().get("sourceCode");
        String type = (String) entity.getMetadataMap().get("type");
        getSources().stream()
                .filter(source -> source.code().equals(sourceCode) || source.type().equals(type))
                .findFirst()
                .ifPresent(source -> {
                    String result = source.ingester().update(entity);
                    sb.append("<b>").append(source.name()).append("</b><br>").append(result);
                });
        return sb.toString();
    }

    public record ManagedSource(
            String code,
            String name,
            String type,
            Ingester ingester
    ) {
    }
}
