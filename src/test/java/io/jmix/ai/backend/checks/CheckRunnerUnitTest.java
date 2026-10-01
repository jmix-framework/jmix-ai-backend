package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckRunnerUnitTest {

    private static final String FIRST_QUESTION = "How does it work?";
    private static final String SECOND_QUESTION = "How do I configure it?";
    private static final Chat.StructuredResponse ACTUAL_ANSWER =
            new Chat.StructuredResponse("Actual answer", List.of(), null, 1, 1, 1);

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private DataManager dataManager;
    @Mock
    private Chat chat;
    @Mock
    private ExternalEvaluator evaluator;

    @Test
    void runChecks_RecordsCohortAndPassesQuestionToEvaluator() {
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

        when(chat.requestStructured(any(), any(), any(), any(), any())).thenReturn(ACTUAL_ANSWER);
        when(evaluator.configurationSnapshot())
                .thenReturn("semantic-evaluator-version-2026-07-28|model=test-judge|temperature=0.0");
        when(evaluator.evaluateSemantic(any(), any(), any(), any())).thenReturn(1.0);

        CheckRunner checkRunner = new CheckRunner(
                dataManager, chat, evaluator, 1, 0.8);
        try {
            checkRunner.runChecks(checkRunId);
        } finally {
            checkRunner.shutdown();
        }

        verify(evaluator).evaluateSemantic(eq(checkDef.getQuestion()), any(), any(), any());
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

        when(chat.requestStructured(any(), any(), any(), any(), any()))
                .thenReturn(new Chat.StructuredResponse("Default \u0000char value.", List.of(), null, 1, 1, 1));
        when(evaluator.evaluateSemantic(any(), any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> logger = invocation.getArgument(3);
            logger.accept("rationale mentions '\u0000' literally");
            return 1.0;
        });

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

    @Test
    void runChecks_ReportsProgressAfterEachCompletedCheck() {
        // Arrange
        CheckRun checkRun = checkRun(JmixVersion.V2);
        Id<CheckRun> checkRunId = Id.of(checkRun);
        when(dataManager.load(checkRunId).one()).thenReturn(checkRun);
        stubActiveV2Definitions(List.of(
                checkDef(FIRST_QUESTION),
                checkDef(SECOND_QUESTION)));
        when(dataManager.create(Check.class)).thenReturn(check(), check());
        when(chat.requestStructured(any(), any(), any(), any(), any())).thenReturn(ACTUAL_ANSWER);
        when(evaluator.evaluateSemantic(any(), any(), any(), any())).thenReturn(1.0);
        List<String> reportedProgress = new ArrayList<>();
        CheckRunProgress progress = (check, completed, total) ->
                reportedProgress.add(check.getQuestion() + " " + completed + "/" + total);
        CheckRunner checkRunner = runnerWithOneThread();

        // Act
        try {
            checkRunner.runChecks(checkRunId, progress);
        } finally {
            checkRunner.shutdown();
        }

        // Assert
        assertThat(reportedProgress)
                .containsExactly(
                        FIRST_QUESTION + " 1/2",
                        SECOND_QUESTION + " 2/2");
    }

    @Test
    void runChecks_InterruptedProgressCancelsTheRun() {
        // Arrange
        CheckRun checkRun = checkRun(JmixVersion.V2);
        Id<CheckRun> checkRunId = Id.of(checkRun);
        when(dataManager.load(checkRunId).one()).thenReturn(checkRun);
        stubActiveV2Definitions(List.of(
                checkDef(FIRST_QUESTION),
                checkDef(SECOND_QUESTION)));
        when(dataManager.create(Check.class)).thenReturn(check(), check());
        when(chat.requestStructured(any(), any(), any(), any(), any())).thenReturn(ACTUAL_ANSWER);
        when(evaluator.evaluateSemantic(any(), any(), any(), any())).thenReturn(1.0);
        CheckRunProgress cancelledProgress = (ignoredCheck, ignoredCompleted, ignoredTotal) -> {
            throw new InterruptedException("cancelled from the dialog");
        };
        CheckRunner checkRunner = runnerWithOneThread();

        // Act
        Throwable failure;
        boolean interruptFlagRestored;
        try {
            failure = catchThrowable(() -> checkRunner.runChecks(checkRunId, cancelledProgress));
        } finally {
            checkRunner.shutdown();
            interruptFlagRestored = Thread.interrupted();
        }

        // Assert
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Check run was cancelled");
        verify(dataManager)
                .remove(checkRunId);
        verify(dataManager, never())
                .save(any(SaveContext.class));
        assertThat(interruptFlagRestored)
                .isTrue();
    }

    @Test
    void countDefinitionsToRun_UsesV2ForARunWithoutVersion() {
        // Arrange
        stubActiveV2Definitions(List.of(
                checkDef(FIRST_QUESTION),
                checkDef(SECOND_QUESTION)));
        CheckRunner checkRunner = runnerWithOneThread();

        // Act
        int definitionsToRun;
        try {
            definitionsToRun = checkRunner.countDefinitionsToRun(checkRun(null));
        } finally {
            checkRunner.shutdown();
        }

        // Assert
        assertThat(definitionsToRun)
                .isEqualTo(2);
    }

    private CheckRunner runnerWithOneThread() {
        return new CheckRunner(
                dataManager,
                chat,
                evaluator,
                1,
                0.8);
    }

    private void stubActiveV2Definitions(List<CheckDef> definitions) {
        when(dataManager.load(CheckDef.class)
                .query("e.active = true and (e.jmixVersion is null or e.jmixVersion = :jmixVersion)")
                .parameter("jmixVersion", JmixVersion.V2.getId())
                .list())
                .thenReturn(definitions);
    }

    private static CheckRun checkRun(JmixVersion jmixVersion) {
        CheckRun checkRun = new CheckRun();
        checkRun.setId(UUID.randomUUID());
        checkRun.setConfigLabel("test-config");
        checkRun.setParameters("description: ignored");
        checkRun.setJmixVersion(jmixVersion);
        return checkRun;
    }

    private static Check check() {
        Check check = new Check();
        check.setId(UUID.randomUUID());
        return check;
    }

    private static CheckDef checkDef(String question) {
        CheckDef checkDef = new CheckDef();
        checkDef.setId(UUID.randomUUID());
        checkDef.setActive(true);
        checkDef.setCategory("test");
        checkDef.setQuestion(question);
        checkDef.setAnswer("Expected answer");
        return checkDef;
    }
}
