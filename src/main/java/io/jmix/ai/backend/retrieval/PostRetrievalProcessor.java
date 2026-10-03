package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.scripting.ScriptEvaluator;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class PostRetrievalProcessor {

    private static final Logger log = LoggerFactory.getLogger(PostRetrievalProcessor.class);

    private final List<Rule> rules;

    @Autowired
    private ScriptEvaluator scriptEvaluator;

    private record Rule(String name, String script) {
    }

    public record Result(List<Document> documents, List<String> notes) {
    }

    public PostRetrievalProcessor(ParametersReader parametersReader) {
        List<Map<String, Object>> ruleMaps = parametersReader.getList("postRetrievalProcessor.rules");
        rules = ruleMaps.stream()
                .map(map -> new Rule((String) map.get("name"), (String) map.get("script")))
                .toList();
    }

    public Result process(String userQuery, List<Document> documents) {
        List<String> notes = new ArrayList<>();
        List<Document> resultList = documents.stream()
                .filter(document -> applyRules(userQuery, document, notes))
                .toList();
        return new Result(resultList, notes);
    }

    private boolean applyRules(String userQuery, Document document, List<String> notes) {
            for (Rule rule : rules) {
                Boolean result = null;
                try {
                    result = (Boolean) scriptEvaluator.evaluate(
                            new StaticScriptSource(rule.script),
                            Map.of("userQuery", userQuery, "document", document)
                    );
                } catch (Exception e) {
                    log.error("Rule {} evaluation failed for document {}", rule.name(), RetrievalUtils.getUrlOrSource(document), e);
                }
                if (result != null && !result) {
                    notes.add("Rule '" + rule.name + "' filtered out " + RetrievalUtils.getUrlOrSource(document));
                    return false;
                }
            }
            return true;
    }
}
