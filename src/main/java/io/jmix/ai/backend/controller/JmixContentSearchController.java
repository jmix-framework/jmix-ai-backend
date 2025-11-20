package io.jmix.ai.backend.controller;

import io.jmix.ai.backend.chat.*;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.jmix.ai.backend.chat.Utils.addLogMessage;
import static io.jmix.ai.backend.chat.Utils.getDistinctDocuments;

@RestController
@RequestMapping("/api/search")
public class JmixContentSearchController {
    private final Logger logger = LoggerFactory.getLogger(JmixContentSearchController.class);

    private final ApplicationContext applicationContext;
    private final VectorStore vectorStore;
    private final Reranker reranker;
    private final ParametersRepository parametersRepository;


    private final Messages messages;

    public JmixContentSearchController(ApplicationContext applicationContext, VectorStore vectorStore, Reranker reranker, ParametersRepository parametersRepository, Messages messages) {
        this.applicationContext = applicationContext;
        this.vectorStore = vectorStore;
        this.reranker = reranker;
        this.parametersRepository = parametersRepository;


        this.messages = messages;
    }

    @PostMapping
    public List<SearchResultDocument> search(@RequestBody SearchRequest request) {
        if (request.type != null) {
            try {
                ToolType.valueOf(request.type.toUpperCase());
            } catch (IllegalArgumentException e) {
                String allowedValues = Arrays.stream(ToolType.values())
                        .map(ToolType::getId)
                        .map(String::toLowerCase)
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException(
                        messages.formatMessage(JmixContentSearchController.class,
                                "illegalType",
                                request.type,
                                allowedValues)
                );
            }
        }

        List<Document> retrievedDocuments = new ArrayList<>();
        final List<AbstractRagTool> ragTools = getRagTools(retrievedDocuments);

        executeTools(ragTools, request.query, request.type);

        List<Document> distinctDocuments = getDistinctDocuments(retrievedDocuments);

        return convertToSearchResults(distinctDocuments);
    }

    private void executeTools(List<AbstractRagTool> ragTools, String query, @Nullable String type) {
        ragTools.stream()
                .filter(tool -> type == null || tool.getToolType().getId().equals(type))
                .forEach(tool -> tool.execute(query));
    }

    private List<SearchResultDocument> convertToSearchResults(List<Document> documents) {
        return documents.stream()
                .map(document -> new SearchResultDocument(
                        UUID.randomUUID().toString(),
                        document.getText(),
                        document.getFormattedContent()))
                .toList();
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

    public record SearchResultDocument(String id, String title, String content) {
    }

    public record SearchRequest(String query, @Nullable String type) {
    }
}
