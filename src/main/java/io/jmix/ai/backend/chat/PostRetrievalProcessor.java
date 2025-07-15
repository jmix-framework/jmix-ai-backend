package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.lang.Nullable;
import org.springframework.scripting.ScriptEvaluator;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class PostRetrievalProcessor {

    private final List<Rule> rules;

    private final Consumer<String> logger;

    @Autowired
    private ScriptEvaluator scriptEvaluator;

    private record Rule(String name, String script) {
    }

    public PostRetrievalProcessor(ParametersReader parametersReader, @Nullable Consumer<String> logger) {
        this.logger = logger;
        List<Map<String, Object>> ruleMaps = parametersReader.getList("postRetrievalProcessor.rules");
        rules = ruleMaps.stream()
                .map(map -> new Rule((String) map.get("name"), (String) map.get("script")))
                .toList();
    }

    public List<Document> process(String userQuery, List<Document> documents) {
        List<Document> resultList = documents.stream()
                .filter(document -> applyRules(userQuery, document))
                .toList();
        return resultList;
    }

    private boolean applyRules(String userQuery, Document document) {
        for (Rule rule : rules) {
            Boolean result = (Boolean) scriptEvaluator.evaluate(
                    new StaticScriptSource(rule.script),
                    Map.of("userQuery", userQuery, "document", document)
            );
            if (result != null && !result) {
                if (logger != null) {
                    logger.accept("Rule '" + rule.name + "' failed for document " + Utils.getUrlOrSource(document));
                }
                return false;
            }
        }
        return true;
    }
}
