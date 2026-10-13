package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckRunnerUnitTest {

    private static final String EVALUATOR_CONFIG = "semantic-evaluator-version-2026-07-28|model=test-judge|temperature=0.0";
    private static final double PASS_THRESHOLD = 0.8;
    private static final String CONFIG_LABEL = "test-config";
    private static final String FIRST_QUESTION = "How does it work?";
    private static final String SECOND_QUESTION = "How do I configure it?";
    private static final String ACTUAL_ANSWER = "Actual answer";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private DataManager dataManager;
    @Mock
    private Chat chat;
    @Mock
    private ExternalEvaluator evaluator;
    @Mock
    private CheckRunProgress progress;
    @Captor
    private ArgumentCaptor<SaveContext> savedContext;

    private CheckRunner checkRunner;
    private CheckRun checkRun;
    private Id<CheckRun> checkRunId;

    @BeforeEach
    void setUp() {
        when(evaluator.configurationSnapshot()).thenReturn(EVALUATOR_CONFIG);
        checkRunner = new CheckRunner(
                dataManager,
                chat,
                evaluator,
                1,
                PASS_THRESHOLD);
        checkRun = checkRunFor(JmixVersion.V2);
        checkRunId = Id.of(checkRun);
    }

    @AfterEach
    void tearDown() {
        checkRunner.shutdown();
    }

    @Test
    void runChecks_RecordsCohortAndPassesQuestionToEvaluator() {
        // Arrange
        CheckDef checkDef = activeDefinition(FIRST_QUESTION);
        givenCheckRunWithActiveDefinitions(checkDef);
        givenChatAnswersEveryQuestionWith(ACTUAL_ANSWER);
        givenJudgeScoresEveryAnswerAsCorrect();

        // Act
        checkRunner.runChecks(checkRunId);

        // Assert
        verify(evaluator)
                .evaluateSemantic(eq(FIRST_QUESTION), any(), any(), any());
        assertThat(checkRun.getEvaluatorConfig())
                .isEqualTo(EVALUATOR_CONFIG);
        assertThat(checkRun.getPassThreshold())
                .isEqualTo(PASS_THRESHOLD);
        assertThat(checkRun.getConfigLabel())
                .isEqualTo(CONFIG_LABEL);
        assertThat(checkRun.getDefinitionFingerprint())
                .isEqualTo(CheckFingerprints.forDefinitions(List.of(checkDef)));
        assertThat(checkRun.getScore())
                .isEqualTo(1.0);
        assertThat(checkRun.getAccuracy())
                .isEqualTo(1.0);
        assertThat(checksSavedWithTheRun())
                .singleElement()
                .extracting(Check::getCheckRun)
                .isSameAs(checkRun);
    }

    /**
     * The chat answer and the judge rationale are model text bound for PostgreSQL TEXT; a NUL
     * in either would fail the single run-wide save and discard the whole paid run.
     */
    @Test
    void runChecks_StripsNulFromAnswerAndExecutionLog() {
        // Arrange
        givenCheckRunWithActiveDefinitions(activeDefinition(FIRST_QUESTION));
        givenChatAnswersEveryQuestionWith("Default \u0000char value.");
        when(evaluator.evaluateSemantic(any(), any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> logger = invocation.getArgument(3);
            logger.accept("rationale mentions '\u0000' literally");
            return 1.0;
        });

        // Act
        checkRunner.runChecks(checkRunId);

        // Assert
        Check savedCheck = checksSavedWithTheRun().iterator().next();
        assertThat(savedCheck.getActualAnswer())
                .isEqualTo("Default char value.");
        assertThat(savedCheck.getLog())
                .doesNotContain("\u0000")
                .contains("rationale mentions");
    }

    @Test
    void runChecks_ReportsProgressAfterEachCompletedCheck() throws InterruptedException {
        // Arrange
        givenCheckRunWithActiveDefinitions(activeDefinition(FIRST_QUESTION), activeDefinition(SECOND_QUESTION));
        givenChatAnswersEveryQuestionWith(ACTUAL_ANSWER);
        givenJudgeScoresEveryAnswerAsCorrect();

        // Act
        checkRunner.runChecks(checkRunId, progress);

        // Assert
        InOrder inOrder = inOrder(progress);
        inOrder.verify(progress)
                .checkCompleted(argThat(check -> FIRST_QUESTION.equals(check.getQuestion())), eq(1), eq(2));
        inOrder.verify(progress)
                .checkCompleted(argThat(check -> SECOND_QUESTION.equals(check.getQuestion())), eq(2), eq(2));
    }

    @Test
    void runChecks_InterruptedProgressCancelsTheRun() throws InterruptedException {
        // Arrange
        givenCheckRunWithActiveDefinitions(activeDefinition(FIRST_QUESTION), activeDefinition(SECOND_QUESTION));
        givenChatAnswersEveryQuestionWith(ACTUAL_ANSWER);
        givenJudgeScoresEveryAnswerAsCorrect();
        doThrow(new InterruptedException("cancelled from the dialog"))
                .when(progress)
                .checkCompleted(any(), anyInt(), anyInt());

        // Act
        Throwable failure = catchThrowable(() -> checkRunner.runChecks(checkRunId, progress));

        // Assert
        assertThat(Thread.interrupted())
                .isTrue();
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Check run was cancelled");
        verify(dataManager)
                .remove(checkRunId);
        verify(dataManager, never())
                .save(any(SaveContext.class));
    }

    @Test
    void countDefinitionsToRun_UsesV2ForARunWithoutVersion() {
        // Arrange
        givenActiveDefinitionsForV2(activeDefinition(FIRST_QUESTION), activeDefinition(SECOND_QUESTION));

        // Act
        int definitionsToRun = checkRunner.countDefinitionsToRun(checkRunWithoutVersion());

        // Assert
        assertThat(definitionsToRun)
                .isEqualTo(2);
    }

    private void givenCheckRunWithActiveDefinitions(CheckDef... activeDefinitions) {
        when(dataManager.load(checkRunId).one()).thenReturn(checkRun);
        givenActiveDefinitionsForV2(activeDefinitions);
        when(dataManager.create(Check.class)).thenAnswer(ignored -> emptyCheck());
    }

    private void givenActiveDefinitionsForV2(CheckDef... activeDefinitions) {
        when(dataManager.load(CheckDef.class)
                .query("e.active = true and (e.jmixVersion is null or e.jmixVersion = :jmixVersion)")
                .parameter("jmixVersion", JmixVersion.V2.getId())
                .list())
                .thenReturn(List.of(activeDefinitions));
    }

    private void givenChatAnswersEveryQuestionWith(String answer) {
        Chat.StructuredResponse response = new Chat.StructuredResponse(answer, List.of(), null, 1, 1, 1);
        when(chat.requestStructured(any(), any(), any(), any(), any())).thenReturn(response);
    }

    private void givenJudgeScoresEveryAnswerAsCorrect() {
        when(evaluator.evaluateSemantic(any(), any(), any(), any())).thenReturn(1.0);
    }

    private Collection<Check> checksSavedWithTheRun() {
        verify(dataManager).save(savedContext.capture());
        return savedContext.getValue()
                .getEntitiesToSave()
                .getAll(Check.class);
    }

    private static CheckRun checkRunWithoutVersion() {
        return checkRunFor(null);
    }

    private static CheckRun checkRunFor(JmixVersion jmixVersion) {
        CheckRun checkRun = new CheckRun();
        checkRun.setId(UUID.randomUUID());
        checkRun.setConfigLabel(CONFIG_LABEL);
        checkRun.setParameters("description: ignored");
        checkRun.setJmixVersion(jmixVersion);
        return checkRun;
    }

    private static Check emptyCheck() {
        Check check = new Check();
        check.setId(UUID.randomUUID());
        return check;
    }

    private static CheckDef activeDefinition(String question) {
        CheckDef checkDef = new CheckDef();
        checkDef.setId(UUID.randomUUID());
        checkDef.setActive(true);
        checkDef.setCategory("test");
        checkDef.setQuestion(question);
        checkDef.setAnswer("Expected answer");
        return checkDef;
    }
}
