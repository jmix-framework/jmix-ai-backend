package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.jmix.ai.backend.retrieval.Utils.addLogMessage;
import static io.jmix.ai.backend.retrieval.Utils.getDistinctDocuments;

@Component
public class SearchService {
    private final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final ParametersRepository parametersRepository;
    private final ToolsManager toolsManager;

    public SearchService(ParametersRepository parametersRepository,
                         ToolsManager toolsManager) {
        this.parametersRepository = parametersRepository;
        this.toolsManager = toolsManager;
    }

    public List<Document> search(String query) {
        List<Document> retrievedDocuments = new ArrayList<>();

        List<String> logMessages = new ArrayList<>();

        Consumer<String> internalLogger = message -> {
            addLogMessage(logger, logMessages, message);
        };

        Parameters parameters = parametersRepository.loadActive(ParametersTargetType.TOOLS);

        List<AbstractRagTool> ragTools = toolsManager.getTools(parameters.getContent(), retrievedDocuments, internalLogger);

        for (AbstractRagTool tool : ragTools) {
            tool.execute(query);
        }

        return getDistinctDocuments(retrievedDocuments);
    }
}
