package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckRunnerUnitTest {

    @Test
    void runChecks_RecordsCohortAndPassesQuestionToEvaluator() {
        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);

        CheckRun checkRun = new CheckRun();
        checkRun.setId(UUID.randomUUID());
        checkRun.setConfigLabel("test-config");
        checkRun.setParameters("description: ignored");
        checkRun.setJmixVersion(JmixVersion.V2);

        CheckDef checkDef = new CheckDef();
        checkDef.setId(UUID.randomUUID());
        checkDef.setActive(true);
        checkDef.setCategory("test");
        checkDef.setQuestion("How does it work?");
        checkDef.setAnswer("Expected answer");

        Check check = new Check();
        Id<CheckRun> checkRunId = Id.of(checkRun);
        when(dataManager.load(checkRunId).one()).thenReturn(checkRun);
        when(dataManager.load(CheckDef.class).query("e.active = true").list()).thenReturn(List.of(checkDef));
        when(dataManager.create(Check.class)).thenReturn(check);

        Chat chat = (question, parameters, conversationId, version, logger) ->
                new Chat.StructuredResponse("Actual answer", List.of(), null, 1, 1, 1);
        AtomicReference<String> evaluatedQuestion = new AtomicReference<>();
        ExternalEvaluator evaluator = (question, referenceAnswer, actualAnswer, logger) -> {
            evaluatedQuestion.set(question);
            return 1.0;
        };

        new CheckRunner(dataManager, chat, evaluator, 1, 0.8).runChecks(checkRunId);

        assertThat(evaluatedQuestion).hasValue(checkDef.getQuestion());
        assertThat(checkRun.getConfigLabel()).isEqualTo(
                CheckRunner.withCohortSuffix("test-config", CheckRunner.buildCohortKey(List.of(checkDef))));
        assertThat(checkRun.getScore()).isEqualTo(1.0);
        assertThat(checkRun.getAccuracy()).isEqualTo(1.0);
        assertThat(check.getCheckRun()).isSameAs(checkRun);
    }
}
