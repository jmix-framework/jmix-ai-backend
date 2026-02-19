package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class CheckRunner {

    private final DataManager dataManager;
    private final Chat chat;
    private final ExternalEvaluator externalEvaluator;

    public CheckRunner(DataManager dataManager, Chat chat, ExternalEvaluator externalEvaluator) {
        this.dataManager = dataManager;
        this.chat = chat;
        this.externalEvaluator = externalEvaluator;
    }

    public void runChecks(Id<CheckRun> checkRunId) {
        CheckRun checkRun = dataManager.load(checkRunId).one();

        List<CheckDef> checkDefs = dataManager.load(CheckDef.class).query("e.active = true").list();

        int count = 0;
        double score = 0.0;
        for (CheckDef checkDef : checkDefs) {
            Check check = runCheck(checkDef, checkRun.getParameters());
            check.setCheckRun(checkRun);
            dataManager.save(check);
            score += check.getScore();
            count++;
        }

        if (count == 0) {
            checkRun.setScore(0.0);
            dataManager.save(checkRun);
            return;
        }

        checkRun.setScore(score / count);
        dataManager.save(checkRun);
    }

    private Check runCheck(CheckDef checkDef, String parameters) {
        StringBuilder logStringBuilder = new StringBuilder();
        Consumer<String> logStringConsumer = str ->
                logStringBuilder.append(str).append("\n");

        String actualAnswer = getAnswer(checkDef.getQuestion(), parameters, logStringConsumer);

        if (!logStringBuilder.isEmpty())
            logStringBuilder.append("\n\n");

        double score = externalEvaluator.evaluateSemantic(
                checkDef.getAnswer(), actualAnswer, logStringBuilder::append);

        Check check = dataManager.create(Check.class);
        check.setCheckDef(checkDef);
        check.setCategory(checkDef.getCategory());
        check.setQuestion(checkDef.getQuestion());
        check.setReferenceAnswer(checkDef.getAnswer());
        check.setActualAnswer(actualAnswer);
        check.setScore(score);
        check.setLog(logStringBuilder.toString());
        return check;
    }

    private String getAnswer(String question, String parameters, Consumer<String> logger) {
        Chat.StructuredResponse response = chat.requestStructured(question, parameters, null, logger);
        return response.text();
    }
}