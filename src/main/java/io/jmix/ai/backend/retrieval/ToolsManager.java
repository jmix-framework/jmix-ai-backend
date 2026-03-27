package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolsManager {

    private final ApplicationContext applicationContext;
    private final ParametersRepository parametersRepository;
    private final VectorStore vectorStore;
    private final Reranker reranker;

    public ToolsManager(ApplicationContext applicationContext, ParametersRepository parametersRepository,
                        VectorStore vectorStore, Reranker reranker) {
        this.applicationContext = applicationContext;
        this.parametersRepository = parametersRepository;
        this.vectorStore = vectorStore;
        this.reranker = reranker;
    }

    public List<AbstractRagTool> getTools(String parametersYaml, List<Document> retrievedDocuments, ToolEventListener listener) {
        ParametersReader parametersReader = parametersRepository.getReader(parametersYaml);

        PostRetrievalProcessor postRetrievalProcessor = applicationContext.getBean(
                PostRetrievalProcessor.class, parametersReader, (java.util.function.Consumer<String>) listener::onLog);

        List<AbstractRagTool> tools = new ArrayList<>();
        if (parametersReader.getBoolean("tools.documentation_retriever.enabled", true)) {
            tools.add(new DocsTool(vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, listener));
        }
        if (parametersReader.getBoolean("tools.uisamples_retriever.enabled", true)) {
            tools.add(new UiSamplesTool(vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, listener));
        }
        if (parametersReader.getBoolean("tools.trainings_retriever.enabled", true)) {
            tools.add(new TrainingsTool(vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, listener));
        }
        return tools;
    }
}
