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
        check.setId(UUID.randomUUID());
        Id<CheckRun> checkRunId = Id.of(checkRun);
        when(dataManager.load(checkRunId).one()).thenReturn(checkRun);
        when(dataManager.load(CheckDef.class)
                .query("e.active = true and (e.jmixVersion is null or e.jmixVersion = :jmixVersion)")
                .parameter("jmixVersion", JmixVersion.V2.getId())
                .list())
                .thenReturn(List.of(checkDef));
        when(dataManager.create(Check.class)).thenReturn(check);

        Chat chat = (question, parameters, conversationId, version, logger) ->
                new Chat.StructuredResponse("Actual answer", List.of(), null, 1, 1, 1);
        AtomicReference<String> evaluatedQuestion = new AtomicReference<>();
        ExternalEvaluator evaluator = new ExternalEvaluator() {
            @Override
            public String configurationSnapshot() {
                return "semantic-evaluator-version-2026-07-28|model=test-judge|temperature=0.0";
            }

            @Override
            public double evaluateSemantic(String question, String referenceAnswer, String actualAnswer,
                                           java.util.function.Consumer<String> logger) {
                evaluatedQuestion.set(question);
                return 1.0;
            }
        };

        CheckRunner checkRunner = new CheckRunner(
                dataManager, chat, evaluator, 1, 0.8);
        try {
            checkRunner.runChecks(checkRunId);
        } finally {
            checkRunner.shutdown();
        }

        assertThat(evaluatedQuestion).hasValue(checkDef.getQuestion());
        assertThat(checkRun.getEvaluatorConfig())
                .isEqualTo("semantic-evaluator-version-2026-07-28|model=test-judge|temperature=0.0");
        assertThat(checkRun.getPassThreshold()).isEqualTo(0.8);
        assertThat(checkRun.getConfigLabel()).isEqualTo("test-config");
        assertThat(checkRun.getDefinitionFingerprint())
                .isEqualTo(CheckFingerprints.forDefinitions(List.of(checkDef)));
        assertThat(checkRun.getScore()).isEqualTo(1.0);
        assertThat(checkRun.getAccuracy()).isEqualTo(1.0);
        assertThat(check.getCheckRun()).isSameAs(checkRun);
    }


    /**
     * The chat answer and the judge rationale are model text bound for PostgreSQL TEXT; a NUL
     * in either would fail the single run-wide save and discard the whole paid run.
     */
    @Test
    void runChecks_StripsNulFromAnswerAndExecutionLog() {
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
        check.setId(UUID.randomUUID());
        Id<CheckRun> checkRunId = Id.of(checkRun);
        when(dataManager.load(checkRunId).one()).thenReturn(checkRun);
        when(dataManager.load(CheckDef.class)
                .query("e.active = true and (e.jmixVersion is null or e.jmixVersion = :jmixVersion)")
                .parameter("jmixVersion", JmixVersion.V2.getId())
                .list())
                .thenReturn(List.of(checkDef));
        when(dataManager.create(Check.class)).thenReturn(check);

        Chat chat = (question, parameters, conversationId, version, logger) ->
                new Chat.StructuredResponse("Default \u0000char value.", List.of(), null, 1, 1, 1);
        ExternalEvaluator evaluator = new ExternalEvaluator() {
            @Override
            public String configurationSnapshot() {
                return "test-evaluator";
            }

            @Override
            public double evaluateSemantic(String question, String referenceAnswer, String actualAnswer,
                                           java.util.function.Consumer<String> logger) {
                logger.accept("rationale mentions '\u0000' literally");
                return 1.0;
            }
        };

        CheckRunner checkRunner = new CheckRunner(
                dataManager, chat, evaluator, 1, 0.8);
        try {
            checkRunner.runChecks(checkRunId);
        } finally {
            checkRunner.shutdown();
        }

        assertThat(check.getActualAnswer()).isEqualTo("Default char value.");
        assertThat(check.getLog()).doesNotContain("\u0000").contains("rationale mentions");
    }
}
