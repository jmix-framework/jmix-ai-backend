package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

@Component
public class CheckRunner {

    private static final Logger log = LoggerFactory.getLogger(CheckRunner.class);

    private final DataManager dataManager;
    private final Chat chat;
    private final ExternalEvaluator externalEvaluator;
    private final ExecutorService executor;
    private final int parallelism;
    private final double passThreshold;
    private final String evaluatorConfig;

    public CheckRunner(DataManager dataManager,
                       Chat chat,
                       ExternalEvaluator externalEvaluator,
                       @Value("${answer-checks.parallelism:4}") int parallelism,
                       @Value("${answer-checks.pass-threshold:0.8}") double passThreshold) {
        this.dataManager = dataManager;
        this.chat = chat;
        this.externalEvaluator = externalEvaluator;
        this.parallelism = Math.max(1, parallelism);
        this.executor = Executors.newFixedThreadPool(this.parallelism);
        this.passThreshold = passThreshold;
        this.evaluatorConfig = externalEvaluator.configurationSnapshot();
    }

    public void runChecks(Id<CheckRun> checkRunId) {
        CheckRun checkRun = dataManager.load(checkRunId).one();
        JmixVersion jmixVersion = checkRun.getJmixVersion() != null ? checkRun.getJmixVersion() : JmixVersion.V2;
        List<Future<Check>> submittedChecks = new ArrayList<>();
        try {
            List<CheckDef> definitions = loadActiveDefinitions(jmixVersion);
            if (definitions.isEmpty()) {
                throw new IllegalStateException("No active check definitions for " + jmixVersion.getId());
            }

            prepareRunMetadata(checkRun, definitions);
            List<Check> completedChecks = executeChecks(
                    definitions, checkRun.getParameters(), jmixVersion, submittedChecks);

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Check run was cancelled");
            }
            saveSuccessfulRun(checkRun, completedChecks);
        } catch (InterruptedException e) {
            cancelSubmittedChecks(submittedChecks);
            deleteFailedRun(checkRunId, e);
            log.info("Check run {} was cancelled", checkRunId);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Check run was cancelled", e);
        } catch (ExecutionException e) {
            cancelSubmittedChecks(submittedChecks);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            deleteFailedRun(checkRunId, cause);
            log.error("Check run {} failed", checkRunId, cause);
            throw propagate(cause);
        } catch (RuntimeException | Error e) {
            cancelSubmittedChecks(submittedChecks);
            deleteFailedRun(checkRunId, e);
            log.error("Check run {} failed", checkRunId, e);
            throw e;
        }
    }

    private void prepareRunMetadata(CheckRun checkRun, List<CheckDef> definitions) {
        String configLabel = CheckConfigLabel.resolve(
                checkRun.getConfigLabel(), checkRun.getParameters());
        checkRun.setEvaluatorConfig(evaluatorConfig);
        checkRun.setPassThreshold(passThreshold);
        checkRun.setDefinitionFingerprint(CheckFingerprints.forDefinitions(definitions));
        checkRun.setConfigLabel(configLabel);
    }

    private List<Check> executeChecks(List<CheckDef> definitions,
                                      String parameters,
                                      JmixVersion jmixVersion,
                                      List<Future<Check>> submittedChecks)
            throws InterruptedException, ExecutionException {
        CompletionService<Check> completedChecks = new ExecutorCompletionService<>(executor);
        Iterator<CheckDef> remainingDefinitions = definitions.iterator();
        while (remainingDefinitions.hasNext() && submittedChecks.size() < parallelism) {
            submittedChecks.add(submitCheck(
                    completedChecks, remainingDefinitions.next(), parameters, jmixVersion));
        }

        List<Check> results = new ArrayList<>(definitions.size());
        for (int i = 0; i < definitions.size(); i++) {
            results.add(completedChecks.take().get());
            if (remainingDefinitions.hasNext()) {
                submittedChecks.add(submitCheck(
                        completedChecks, remainingDefinitions.next(), parameters, jmixVersion));
            }
        }
        return results;
    }

    private Check evaluateDefinition(CheckDef checkDef, String parameters, JmixVersion jmixVersion) {
        StringBuilder executionLog = new StringBuilder();
        Consumer<String> chatLogger = message -> executionLog.append(message).append("\n");

        String actualAnswer = requestAnswer(checkDef.getQuestion(), parameters, jmixVersion, chatLogger);

        if (!executionLog.isEmpty()) {
            executionLog.append("\n\n");
        }

        double score = externalEvaluator.evaluateSemantic(
                checkDef.getQuestion(), checkDef.getAnswer(), actualAnswer, executionLog::append);

        return createCheck(checkDef, actualAnswer, score, executionLog.toString());
    }

    private Future<Check> submitCheck(CompletionService<Check> completionService,
                                      CheckDef checkDef,
                                      String parameters,
                                      JmixVersion jmixVersion) {
        return completionService.submit(() ->
                evaluateDefinition(checkDef, parameters, jmixVersion));
    }

    private Check createCheck(CheckDef checkDef, String actualAnswer, double score, String log) {
        Check check = dataManager.create(Check.class);
        check.setCheckDef(checkDef);
        check.setCategory(checkDef.getCategory());
        check.setQuestion(checkDef.getQuestion());
        check.setReferenceAnswer(checkDef.getAnswer());
        check.setActualAnswer(actualAnswer);
        check.setScore(score);
        check.setLog(log);
        return check;
    }

    private List<CheckDef> loadActiveDefinitions(JmixVersion jmixVersion) {
        return dataManager.load(CheckDef.class)
                .query("e.active = true and (e.jmixVersion is null or e.jmixVersion = :jmixVersion)")
                .parameter("jmixVersion", jmixVersion.getId())
                .list();
    }

    private void saveSuccessfulRun(CheckRun checkRun, List<Check> checks) {
        double score = 0.0;
        int passed = 0;
        for (Check check : checks) {
            check.setCheckRun(checkRun);
            score += check.getScore();
            if (check.getScore() >= passThreshold) {
                passed++;
            }
        }
        checkRun.setScore(score / checks.size());
        checkRun.setAccuracy((double) passed / checks.size());

        SaveContext saveContext = new SaveContext().saving(checkRun);
        saveContext.saving(checks.toArray());
        dataManager.save(saveContext);
    }

    private static void cancelSubmittedChecks(List<? extends Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private void deleteFailedRun(Id<CheckRun> checkRunId, Throwable failure) {
        try {
            dataManager.remove(checkRunId);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.error("Failed to delete unsuccessful check run {}", checkRunId, cleanupFailure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Check execution failed", failure);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private String requestAnswer(String question, String parameters, JmixVersion jmixVersion,
                                 Consumer<String> logger) {
        Chat.StructuredResponse response = chat.requestStructured(question, parameters, null, jmixVersion, logger);
        return response.text();
    }
}
