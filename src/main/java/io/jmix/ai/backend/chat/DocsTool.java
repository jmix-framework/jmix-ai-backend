package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.Parameters;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Objects;

public class DocsTool extends AbstractRagTool {


    protected DocsTool(VectorStore vectorStore, Parameters parameters) {
        super(vectorStore, "docs", "url",
                parameters.getDocsToolDescription(), parameters.getDocsToolSimilarityThreshold(), parameters.getDocsToolTopK());
    }

    @Override
    public ToolCallback getToolCallback() {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(DocsTool.class, "execute", String.class));

        ToolCallback toolCallback = MethodToolCallback.builder()
                .toolDefinition(ToolDefinition.builder()
                        .name("jmix_documentation_retriever")
                        .description(description)
                        .inputSchema(JsonSchemaGenerator.generateForMethodInput(method))
                        .build())
                .toolObject(this)
                .toolMethod(method)
                .build();
        return toolCallback;
    }

    public String execute(String queryText) {
        return executeSearch(queryText, similarityThreshold, topK);
    }

    @Override
    protected String getNoResultsMessage() {
        return "No documentation found for the query. Try using another tool or rephrasing your query.";
    }
}
