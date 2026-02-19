package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scripting.ScriptEvaluator;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class CheckRunner {

    private static final Logger log = LoggerFactory.getLogger(CheckRunner.class);
    private final DataManager dataManager;
    private final Chat chat;
    private final ScriptEvaluator scriptEvaluator;
    private final ExternalEvaluator externalEvaluator;

    public CheckRunner(DataManager dataManager, Chat chat, ScriptEvaluator scriptEvaluator, ExternalEvaluator externalEvaluator) {
        this.dataManager = dataManager;
        this.chat = chat;
        this.scriptEvaluator = scriptEvaluator;
        this.externalEvaluator = externalEvaluator;
    }

    public void runChecks(Id<CheckRun> checkRunId) {
        CheckRun checkRun = dataManager.load(checkRunId).one();

        List<CheckDef> checkDefs = dataManager.load(CheckDef.class).query("e.active = true").list();

        int count = 0;
        double scriptScore = 0.0;
        double semanticScore = 0.0;
        for (CheckDef checkDef : checkDefs) {
            Check check = runCheck(checkDef, checkRun.getParameters());
            check.setCheckRun(checkRun);
            dataManager.save(check);
            scriptScore += check.getScriptScore();
            semanticScore += check.getSemanticScore();
            count++;
        }

        if (count == 0) {
            checkRun.setScriptScore(0.0);
            checkRun.setSemanticScore(0.0);
            dataManager.save(checkRun);
            return;
        }

        checkRun.setScriptScore(scriptScore / count);
        checkRun.setSemanticScore(semanticScore / count);
        dataManager.save(checkRun);
    }

    private Check runCheck(CheckDef checkDef, String parameters) {
        StringBuilder logStringBuilder = new StringBuilder();
        Consumer<String> logStringConsumer = str ->
                logStringBuilder.append(str).append("\n");

        String actualAnswer = getAnswer(checkDef.getQuestion(), parameters, logStringConsumer);

        if (!logStringBuilder.isEmpty())
            logStringBuilder.append("\n\n");

        double scriptScore = runScript(checkDef.getAnswer(), actualAnswer, checkDef.getScript(), logStringBuilder::append);

        if (!logStringBuilder.isEmpty())
            logStringBuilder.append("\n\n");

        double semanticScore = externalEvaluator.evaluateSemantic(
                checkDef.getAnswer(), actualAnswer, logStringBuilder::append);

        Check check = dataManager.create(Check.class);
        check.setCheckDef(checkDef);
        check.setCategory(checkDef.getCategory());
        check.setScript(checkDef.getScript());
        check.setQuestion(checkDef.getQuestion());
        check.setReferenceAnswer(checkDef.getAnswer());
        check.setActualAnswer(actualAnswer);
        check.setScriptScore(scriptScore);
        check.setSemanticScore(semanticScore);
        check.setLog(logStringBuilder.toString());
        return check;
    }

    private String getAnswer(String question, String parameters, Consumer<String> logger) {
        Chat.StructuredResponse response = chat.requestStructured(question, parameters, null, logger);
        return response.text();
    }

    private double runScript(String referenceAnswer, String actualAnswer, String script, Consumer<String> logger) {
        if (StringUtils.isBlank(script)) {
            return 0.0;
        }
        Number result;
        try {
            result = (Number) scriptEvaluator.evaluate(
                    new StaticScriptSource(script),
                    Map.of("referenceAnswer", referenceAnswer, "actualAnswer", actualAnswer)
            );
        } catch (Exception e) {
            log.error("Failed to evaluate script: {}", script, e);
            logger.accept("Failed to evaluate script: " + e.getMessage());
            return 0.0;
        }
        return result != null ? result.doubleValue() : 0.0;
    }
}