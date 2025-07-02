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

public class UiSamplesTool extends AbstractRagTool {

    public UiSamplesTool(VectorStore vectorStore, Parameters parameters) {
        super(vectorStore, "uisamples", "url",
                parameters.getUiSamplesToolDescription(), parameters.getUiSamplesToolSimilarityThreshold(), parameters.getUiSamplesToolTopK());
    }

    @Override
    public ToolCallback getToolCallback() {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(UiSamplesTool.class, "execute", String.class));

        ToolCallback toolCallback = MethodToolCallback.builder()
                .toolDefinition(ToolDefinition.builder()
                        .name("ui_samples_retriever")
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
        return "No UI Samples found for the query. Try using another tool or rephrasing your query.";
    }
}
