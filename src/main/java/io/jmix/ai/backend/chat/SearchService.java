package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.controller.SearchController;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.parameters.ParametersReader;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.core.Messages;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.jmix.ai.backend.chat.Utils.addLogMessage;
import static io.jmix.ai.backend.chat.Utils.getDistinctDocuments;

@Component
public class SearchService {
    private final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final ApplicationContext applicationContext;
    private final VectorStore vectorStore;
    private final Reranker reranker;
    private final ParametersRepository parametersRepository;
    private final Messages messages;

    public SearchService(ApplicationContext applicationContext,
                         VectorStore vectorStore,
                         Reranker reranker,
                         ParametersRepository parametersRepository,
                         Messages messages) {
        this.applicationContext = applicationContext;
        this.vectorStore = vectorStore;
        this.reranker = reranker;
        this.parametersRepository = parametersRepository;
        this.messages = messages;
    }

    public List<Document> search(String query, @Nullable String type) {
        List<Document> retrievedDocuments = new ArrayList<>();
        final List<AbstractRagTool> ragTools = getRagTools(retrievedDocuments);

        if (type != null) {
            var availableTypes = ragTools.stream()
                    .map(it -> it.type)
                    .collect(Collectors.toSet());
            if(!availableTypes.contains(type)){
                throw new IllegalArgumentException(
                        messages.formatMessage(SearchController.class,
                                "illegalType",
                                type,
                                availableTypes)
                );
            }
        }


        executeTools(ragTools, query, type);

        return getDistinctDocuments(retrievedDocuments);
    }

    private void executeTools(List<AbstractRagTool> ragTools, String query, @Nullable String requestedType) {
        ragTools.stream()
                .filter(tool -> requestedType == null || tool.type.equals(requestedType))
                .forEach(tool -> tool.execute(query));
    }

    private List<AbstractRagTool> getRagTools(List<Document> retrievedDocuments) {
        List<String> logMessages = new ArrayList<>();

        Consumer<String> internalLogger = message -> {
            addLogMessage(logger, logMessages, message);
        };

        Parameters parameters = parametersRepository.loadActive();
        ParametersReader parametersReader = parametersRepository.getReader(parameters);
        PostRetrievalProcessor postRetrievalProcessor = applicationContext.getBean(PostRetrievalProcessor.class, parametersReader, internalLogger);

        final List<AbstractRagTool> ragTools = new ArrayList<>();
        DocsTool docsTool = new DocsTool(vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, internalLogger);
        UiSamplesTool uiSamplesTool = new UiSamplesTool(vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, internalLogger);
        TrainingsTool trainingsTool = new TrainingsTool(vectorStore, postRetrievalProcessor, reranker, parametersReader, retrievedDocuments, internalLogger);

        ragTools.add(docsTool);
        ragTools.add(uiSamplesTool);
        ragTools.add(trainingsTool);
        return ragTools;
    }
}
