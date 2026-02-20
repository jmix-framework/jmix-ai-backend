package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

@Component
public class CheckRunner {

    private final DataManager dataManager;
    private final Chat chat;
    private final ExternalEvaluator externalEvaluator;
    private final int parallelism;

    public CheckRunner(DataManager dataManager,
                       Chat chat,
                       ExternalEvaluator externalEvaluator,
                       @Value("${answer-checks.parallelism:4}") int parallelism) {
        this.dataManager = dataManager;
        this.chat = chat;
        this.externalEvaluator = externalEvaluator;
        this.parallelism = Math.max(1, parallelism);
    }

    public void runChecks(Id<CheckRun> checkRunId) {
        CheckRun checkRun = dataManager.load(checkRunId).one();
        List<CheckDef> checkDefs = dataManager.load(CheckDef.class).query("e.active = true").list();
        if (checkDefs.isEmpty()) {
            checkRun.setScore(0.0);
            dataManager.save(checkRun);
            return;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(parallelism);
        List<Check> checks = new ArrayList<>();
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
                    Thread.currentThread().interrupt();
                    checks.add(failedCheck(checkDef, "Check execution interrupted: " + e.getMessage()));
                } catch (ExecutionException e) {
                    checks.add(failedCheck(checkDef, "Check execution failed: " + e.getCause()));
                }
            }
        } finally {
            executorService.shutdown();
        }

        double score = 0.0;
        int count = 0;
        for (Check check : checks) {
            check.setCheckRun(checkRun);
            dataManager.save(check);
            score += check.getScore();
            count++;
        }
        checkRun.setScore(score / count);
        dataManager.save(checkRun);
    }

    private Check runCheck(CheckDef checkDef, String parameters) {
        StringBuilder logStringBuilder = new StringBuilder();
        try {
            Consumer<String> logStringConsumer = str ->
                    logStringBuilder.append(str).append("\n");

            String actualAnswer = getAnswer(checkDef.getQuestion(), parameters, logStringConsumer);

            if (!logStringBuilder.isEmpty())
                logStringBuilder.append("\n\n");

            double score = externalEvaluator.evaluateSemantic(
                    checkDef.getAnswer(), actualAnswer, logStringBuilder::append);

            return buildCheck(checkDef, actualAnswer, score, logStringBuilder.toString());
        } catch (Exception e) {
            return failedCheck(checkDef, "Check failed: " + e.getMessage(), logStringBuilder);
        }
    }

    private Check failedCheck(CheckDef checkDef, String message) {
        return failedCheck(checkDef, message, new StringBuilder());
    }

    private Check failedCheck(CheckDef checkDef, String message, StringBuilder logStringBuilder) {
        if (!logStringBuilder.isEmpty()) {
            logStringBuilder.append("\n");
        }
        logStringBuilder.append(message);
        return buildCheck(checkDef, "", 0.0, logStringBuilder.toString());
    }

    private Check buildCheck(CheckDef checkDef, String actualAnswer, double score, String log) {
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

    private String getAnswer(String question, String parameters, Consumer<String> logger) {
        Chat.StructuredResponse response = chat.requestStructured(question, parameters, null, logger);
        return response.text();
    }
}
