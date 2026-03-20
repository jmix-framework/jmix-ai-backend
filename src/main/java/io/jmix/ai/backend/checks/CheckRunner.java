package io.jmix.ai.backend.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.CheckRunStatus;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

@Component
public class CheckRunner {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final DataManager dataManager;
    private final Chat chat;
    private final ExternalEvaluator externalEvaluator;
    private final int parallelism;
    private final String datasetVersion;

    public CheckRunner(DataManager dataManager,
                       Chat chat,
                       ExternalEvaluator externalEvaluator,
                       @Value("${answer-checks.parallelism:4}") int parallelism,
                       @Value("${answer-checks.dataset-version:unknown}") String datasetVersion) {
        this.dataManager = dataManager;
        this.chat = chat;
        this.externalEvaluator = externalEvaluator;
        this.parallelism = Math.max(1, parallelism);
        this.datasetVersion = datasetVersion;
    }

    public void runChecks(Id<CheckRun> checkRunId) {
        CheckRun checkRun = dataManager.load(checkRunId).one();
        populateRunMetadata(checkRun);
        if (!externalEvaluator.isAvailable()) {
            failRun(checkRun, "Semantic evaluator is not configured");
            return;
        }

        List<CheckDef> checkDefs = loadCheckDefs(checkRun);
        if (checkDefs.isEmpty()) {
            checkRun.setScore(0.0);
            checkRun.setStatus(CheckRunStatus.SUCCESS);
            markRunFinished(checkRun);
            dataManager.save(checkRun);
            return;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(parallelism);
        List<Check> checks = new ArrayList<>();
        String infrastructureFailure = null;
        try {
            List<Future<Check>> futures = checkDefs.stream()
                    .map(checkDef ->
                            executorService.submit(() ->
                                    runCheck(checkDef, checkRun.getParameters())
                            )
                    )
                    .toList();

            for (int i = 0; i < futures.size(); i++) {
                CheckDef checkDef = checkDefs.get(i);
                try {
                    checks.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    if (infrastructureFailure == null) {
                        infrastructureFailure = "Check execution interrupted";
                    }
                    checks.add(failedCheck(checkDef, "Check execution interrupted"));
                    for (int j = i + 1; j < futures.size(); j++) {
                        futures.get(j).cancel(true);
                        checks.add(failedCheck(checkDefs.get(j), "Check execution interrupted"));
                    }
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CheckInfrastructureException infrastructureException) {
                        if (infrastructureFailure == null) {
                            infrastructureFailure = infrastructureException.getMessage();
                        }
                        checks.add(infrastructureException.failedCheck());
                    } else if (cause instanceof ExternalEvaluatorException || cause instanceof ChatInfrastructureException) {
                        if (infrastructureFailure == null) {
                            infrastructureFailure = cause.getMessage();
                        }
                        checks.add(failedCheck(checkDef, "Check execution failed: " + cause.getMessage()));
                    } else {
                        checks.add(failedCheck(checkDef, "Check execution failed: " + cause));
                    }
                }
            }
        } finally {
            executorService.shutdown();
        }

        double score = 0.0;
        int count = 0;
        long modelResponseTotalMs = 0;
        boolean hasModelResponseTiming = false;
        for (Check check : checks) {
            check.setCheckRun(checkRun);
            dataManager.save(check);
            score += check.getScore();
            if (check.getModelResponseTimeMs() != null) {
                modelResponseTotalMs += check.getModelResponseTimeMs();
                hasModelResponseTiming = true;
            }
            count++;
        }
        checkRun.setModelResponseTotalMs(hasModelResponseTiming ? modelResponseTotalMs : null);

        if (infrastructureFailure != null) {
            failRun(checkRun, infrastructureFailure);
            return;
        }

        checkRun.setScore(score / count);
        checkRun.setStatus(CheckRunStatus.SUCCESS);
        checkRun.setFailureReason(null);
        markRunFinished(checkRun);
        dataManager.save(checkRun);
    }

    private Check runCheck(CheckDef checkDef, String parameters) {
        StringBuilder logStringBuilder = new StringBuilder();
        String actualAnswer = "";
        long modelResponseTimeMs = 0;
        try {
            Consumer<String> logStringConsumer = str ->
                    logStringBuilder.append(str).append("\n");

            try {
                Chat.StructuredResponse response = getAnswer(checkDef.getQuestion(), parameters, logStringConsumer);
                actualAnswer = response.text();
                modelResponseTimeMs = Math.max(response.responseTime(), 0);
                if (actualAnswer == null || actualAnswer.isBlank()) {
                    throw new ChatInfrastructureException("Chat returned empty answer", new IllegalStateException("empty answer"));
                }
            } catch (Exception e) {
                throw new ChatInfrastructureException("Chat request failed: " + e.getMessage(), e);
            }

            if (!logStringBuilder.isEmpty())
                logStringBuilder.append("\n\n");

            double score = externalEvaluator.evaluateSemantic(
                    checkDef.getAnswer(), actualAnswer, logStringBuilder::append);

            return buildCheck(checkDef, actualAnswer, modelResponseTimeMs, score, logStringBuilder.toString());
        } catch (ExternalEvaluatorException | ChatInfrastructureException e) {
            throw new CheckInfrastructureException(
                    e.getMessage(),
                    failedCheck(checkDef, actualAnswer, modelResponseTimeMs,
                            "Check execution failed: " + e.getMessage(), logStringBuilder),
                    e
            );
        } catch (Exception e) {
            return failedCheck(checkDef, actualAnswer, modelResponseTimeMs, "Check failed: " + e.getMessage(), logStringBuilder);
        }
    }

    private void populateRunMetadata(CheckRun checkRun) {
        checkRun.setDatasetVersion(datasetVersion);
        JsonNode parametersRoot = parseParameters(checkRun.getParameters());
        checkRun.setAnswerModel(extractAnswerModel(parametersRoot));
        checkRun.setRetrievalProfile(extractRetrievalProfile(parametersRoot));
        checkRun.setPromptRevision(extractPromptRevision(parametersRoot));
        checkRun.setKnowledgeSnapshot(extractKnowledgeSnapshot(parametersRoot));
        checkRun.setEvaluatorModel(externalEvaluator.getModelName());
        checkRun.setEvaluatorEndpoint(externalEvaluator.getEndpoint());
        checkRun.setStatus(CheckRunStatus.RUNNING);
        checkRun.setFailureReason(null);
        checkRun.setFinishedAt(null);
        checkRun.setDurationMs(null);
        checkRun.setModelResponseTotalMs(null);
        checkRun.setScore(null);
        dataManager.save(checkRun);
    }

    private List<CheckDef> loadCheckDefs(CheckRun checkRun) {
        if (Boolean.TRUE.equals(checkRun.getGoldenOnly())) {
            return dataManager.load(CheckDef.class)
                    .query("e.active = true and e.golden = true")
                    .list();
        }
        return dataManager.load(CheckDef.class)
                .query("e.active = true")
                .list();
    }

    private JsonNode parseParameters(String parametersYaml) {
        if (parametersYaml == null || parametersYaml.isBlank()) {
            return null;
        }
        try {
            return YAML_MAPPER.readTree(parametersYaml);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractAnswerModel(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode modelNode = root.path("model").path("name");
        return modelNode.isTextual() ? modelNode.asText() : null;
    }

    private String extractRetrievalProfile(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode explicitValue = root.path("retrievalProfile");
        if (explicitValue.isTextual()) {
            return explicitValue.asText();
        }

        JsonNode toolsNode = root.path("tools");
        if (!toolsNode.isObject()) {
            return null;
        }
        List<String> enabledTools = StreamSupport.stream(toolsNode.properties().spliterator(), false)
                .map(entry -> {
                    String toolName = entry.getKey();
                    JsonNode toolNode = entry.getValue();
                    if ("skipForTrivialPrompts".equals(toolName)) {
                        return null;
                    }
                    if (toolNode.isObject() && toolNode.path("enabled").isBoolean() && !toolNode.path("enabled").asBoolean()) {
                        return null;
                    }
                    return toolName;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (enabledTools.isEmpty()) {
            return null;
        }
        return String.join(", ", enabledTools);
    }

    private String extractPromptRevision(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode explicitValue = root.path("promptRevision");
        if (explicitValue.isTextual()) {
            return explicitValue.asText();
        }
        JsonNode systemMessage = root.path("systemMessage");
        if (!systemMessage.isTextual() || systemMessage.asText().isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(systemMessage.asText().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (int i = 0; i < 6; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractKnowledgeSnapshot(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode explicitValue = root.path("knowledgeSnapshot");
        return explicitValue.isTextual() ? explicitValue.asText() : null;
    }

    private void failRun(CheckRun checkRun, String failureReason) {
        checkRun.setStatus(CheckRunStatus.FAILED);
        checkRun.setFailureReason(failureReason);
        checkRun.setScore(null);
        markRunFinished(checkRun);
        dataManager.save(checkRun);
    }

    private void markRunFinished(CheckRun checkRun) {
        OffsetDateTime finishedAt = OffsetDateTime.now();
        checkRun.setFinishedAt(finishedAt);
        if (checkRun.getCreatedDate() != null) {
            checkRun.setDurationMs(Duration.between(checkRun.getCreatedDate(), finishedAt).toMillis());
        } else {
            checkRun.setDurationMs(null);
        }
    }

    private Check failedCheck(CheckDef checkDef, String message) {
        return failedCheck(checkDef, "", 0, message, new StringBuilder());
    }

    private Check failedCheck(CheckDef checkDef, String message, StringBuilder logStringBuilder) {
        return failedCheck(checkDef, "", 0, message, logStringBuilder);
    }

    private Check failedCheck(CheckDef checkDef, String actualAnswer, long modelResponseTimeMs,
                              String message, StringBuilder logStringBuilder) {
        if (!logStringBuilder.isEmpty()) {
            logStringBuilder.append("\n");
        }
        logStringBuilder.append(message);
        return buildCheck(checkDef, actualAnswer, modelResponseTimeMs, 0.0, logStringBuilder.toString());
    }

    private Check buildCheck(CheckDef checkDef, String actualAnswer, long modelResponseTimeMs, double score, String log) {
        Check check = dataManager.create(Check.class);
        check.setCheckDef(checkDef);
        check.setCategory(checkDef.getCategory());
        check.setQuestion(checkDef.getQuestion());
        check.setReferenceAnswer(checkDef.getAnswer());
        check.setActualAnswer(actualAnswer);
        check.setModelResponseTimeMs(modelResponseTimeMs);
        check.setScore(score);
        check.setLog(log);
        return check;
    }

    private Chat.StructuredResponse getAnswer(String question, String parameters, Consumer<String> logger) {
        return chat.requestStructured(question, parameters, null, logger);
    }

    private static class ChatInfrastructureException extends RuntimeException {
        private ChatInfrastructureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class CheckInfrastructureException extends RuntimeException {
        private final Check failedCheck;

        private CheckInfrastructureException(String message, Check failedCheck, Throwable cause) {
            super(message, cause);
            this.failedCheck = failedCheck;
        }

        private Check failedCheck() {
            return failedCheck;
        }
    }
}
