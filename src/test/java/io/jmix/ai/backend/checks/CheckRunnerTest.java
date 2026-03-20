package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import test_support.AuthenticatedAsAdmin;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AuthenticatedAsAdmin.class)
public class CheckRunnerTest {

    @Autowired
    DataManager dataManager;
    @Autowired
    DataSource dataSource;

    CheckDef checkDef1;
    CheckDef checkDef2;

    @BeforeEach
    void setUp() {
        clearTables();

        checkDef1 = dataManager.create(CheckDef.class);
        checkDef1.setCategory("basic");
        checkDef1.setQuestion("What is the answer?");
        checkDef1.setAnswer("42");
        checkDef1.setActive(true);
        dataManager.save(checkDef1);

        checkDef2 = dataManager.create(CheckDef.class);
        checkDef2.setCategory("basic");
        checkDef2.setQuestion("Who are you?");
        checkDef2.setAnswer("Jmix AI");
        checkDef2.setActive(true);
        dataManager.save(checkDef2);
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("delete from CHECK_");
        jdbc.execute("delete from CHECK_RUN");
        jdbc.execute("delete from CHECK_DEF");
    }

    @Test
    void test() {
        CheckRunner checkRunner = new CheckRunner(dataManager, new TestChat(), new TestExternalEvaluator(), 4, "dataset-v1");

        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("""
                model:
                  name: test-answer-model
                retrievalProfile: docs+ui
                promptRevision: prompt-v3
                knowledgeSnapshot: kb-2026-03-16
                """);
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).isNotEmpty();

        Check check1 = checks.stream().filter(c -> c.getCheckDef().equals(checkDef1)).findFirst().orElseThrow();
        assertThat(check1.getCheckRun()).isEqualTo(checkRun);
        assertThat(check1.getCategory()).isEqualTo(checkDef1.getCategory());
        assertThat(check1.getModelResponseTimeMs()).isEqualTo(1000);
        assertThat(check1.getScore()).isEqualTo(1.0);

        Check check2 = checks.stream().filter(c -> c.getCheckDef().equals(checkDef2)).findFirst().orElseThrow();
        assertThat(check2.getCheckRun()).isEqualTo(checkRun);
        assertThat(check2.getCategory()).isEqualTo(checkDef2.getCategory());
        assertThat(check2.getModelResponseTimeMs()).isEqualTo(1000);
        assertThat(check2.getScore()).isEqualTo(0.0);

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getScore()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(updatedCheckRun.getDatasetVersion()).isEqualTo("dataset-v1");
        assertThat(updatedCheckRun.getAnswerModel()).isEqualTo("test-answer-model");
        assertThat(updatedCheckRun.getRetrievalProfile()).isEqualTo("docs+ui");
        assertThat(updatedCheckRun.getPromptRevision()).isEqualTo("prompt-v3");
        assertThat(updatedCheckRun.getKnowledgeSnapshot()).isEqualTo("kb-2026-03-16");
        assertThat(updatedCheckRun.getEvaluatorModel()).isEqualTo("test-evaluator");
        assertThat(updatedCheckRun.getModelResponseTotalMs()).isEqualTo(2000);
        assertThat(updatedCheckRun.getStatus()).isEqualTo(io.jmix.ai.backend.entity.CheckRunStatus.SUCCESS);
    }

    @Test
    void runChecks_whenMetadataNotSpecified_derivesPromptAndRetrievalContext() {
        CheckRunner checkRunner = new CheckRunner(dataManager, new TestChat(), new TestExternalEvaluator(), 4, "dataset-v1");

        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("""
                model:
                  name: derived-model
                tools:
                  documentation_retriever:
                    topK: 3
                  uisamples_retriever:
                    topK: 2
                  trainings_retriever:
                    enabled: false
                systemMessage: test system message
                """);
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getAnswerModel()).isEqualTo("derived-model");
        assertThat(updatedCheckRun.getRetrievalProfile()).isEqualTo("documentation_retriever, uisamples_retriever");
        assertThat(updatedCheckRun.getPromptRevision()).startsWith("sha256:");
        assertThat(updatedCheckRun.getKnowledgeSnapshot()).isNull();
    }

    @Test
    void runChecks_parallelExecutionWith20Defs() {
        clearTables();
        List<CheckDef> defs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            CheckDef checkDef = dataManager.create(CheckDef.class);
            checkDef.setCategory("batch");
            checkDef.setQuestion("Question " + i);
            checkDef.setAnswer("Answer " + i);
            checkDef.setActive(true);
            defs.add(checkDef);
        }
        dataManager.save(defs.toArray());

        CheckRunner checkRunner = new CheckRunner(dataManager, new EchoChat(), new TestExternalEvaluator(), 4, "dataset-v1");
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).hasSize(20);

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(updatedCheckRun.getStatus()).isEqualTo(io.jmix.ai.backend.entity.CheckRunStatus.SUCCESS);
    }

    @Test
    void runChecks_whenGoldenOnly_runsOnlyGoldenSubset() {
        clearTables();

        CheckDef goldenDef = dataManager.create(CheckDef.class);
        goldenDef.setCategory("basic");
        goldenDef.setQuestion("golden");
        goldenDef.setAnswer("golden");
        goldenDef.setActive(true);
        goldenDef.setGolden(true);

        CheckDef nonGoldenDef = dataManager.create(CheckDef.class);
        nonGoldenDef.setCategory("basic");
        nonGoldenDef.setQuestion("non-golden");
        nonGoldenDef.setAnswer("non-golden");
        nonGoldenDef.setActive(true);
        nonGoldenDef.setGolden(false);

        dataManager.save(goldenDef, nonGoldenDef);

        CheckRunner checkRunner = new CheckRunner(dataManager, new EchoChat(), new TestExternalEvaluator(), 4, "dataset-v1");
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        checkRun.setGoldenOnly(true);
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).hasSize(1);
        assertThat(checks.getFirst().getQuestion()).isEqualTo("golden");
    }

    @Test
    void runChecks_whenChatRequestFails_marksRunFailed() {
        clearTables();

        CheckDef successDef = dataManager.create(CheckDef.class);
        successDef.setCategory("basic");
        successDef.setQuestion("ok");
        successDef.setAnswer("ok");
        successDef.setActive(true);

        CheckDef failingDef = dataManager.create(CheckDef.class);
        failingDef.setCategory("basic");
        failingDef.setQuestion("fail");
        failingDef.setAnswer("ignored");
        failingDef.setActive(true);

        dataManager.save(successDef, failingDef);

        CheckRunner checkRunner = new CheckRunner(dataManager, new FailingChat(), new TestExternalEvaluator(), 4, "dataset-v1");
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).hasSize(2);

        Check failedCheck = checks.stream()
                .filter(c -> c.getQuestion().equals("fail"))
                .findFirst()
                .orElseThrow();
        assertThat(failedCheck.getScore()).isEqualTo(0.0);
        assertThat(failedCheck.getLog()).contains("Chat request failed");

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getScore()).isNull();
        assertThat(updatedCheckRun.getStatus()).isEqualTo(io.jmix.ai.backend.entity.CheckRunStatus.FAILED);
        assertThat(updatedCheckRun.getFailureReason()).contains("Chat request failed");
    }

    @Test
    void runChecks_whenEvaluatorUnavailable_marksRunFailed() {
        CheckRunner checkRunner = new CheckRunner(dataManager, new TestChat(), new UnavailableExternalEvaluator(), 4, "dataset-v1");

        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getStatus()).isEqualTo(io.jmix.ai.backend.entity.CheckRunStatus.FAILED);
        assertThat(updatedCheckRun.getFailureReason()).contains("not configured");
        assertThat(updatedCheckRun.getScore()).isNull();
    }

    @Test
    void runChecks_whenChatReturnsEmptyAnswer_marksRunFailed() {
        CheckRunner checkRunner = new CheckRunner(dataManager, new EmptyChat(), new TestExternalEvaluator(), 4, "dataset-v1");

        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getStatus()).isEqualTo(io.jmix.ai.backend.entity.CheckRunStatus.FAILED);
        assertThat(updatedCheckRun.getFailureReason()).contains("Chat request failed: Chat returned empty answer");
        assertThat(updatedCheckRun.getScore()).isNull();
    }

    @Test
    void runChecks_whenInterrupted_marksRunFailed() throws Exception {
        CheckRunner checkRunner = new CheckRunner(dataManager, new SlowChat(), new TestExternalEvaluator(), 1, "dataset-v1");

        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        Thread testThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                SlowChat.STARTED.await(1, TimeUnit.SECONDS);
                testThread.interrupt();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        interrupter.start();

        try {
            checkRunner.runChecks(Id.of(checkRun));
        } finally {
            interrupter.join(1000);
            Thread.interrupted();
        }

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getStatus()).isEqualTo(io.jmix.ai.backend.entity.CheckRunStatus.FAILED);
        assertThat(updatedCheckRun.getFailureReason()).contains("interrupted");
        assertThat(updatedCheckRun.getScore()).isNull();

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).allMatch(check -> check.getScore() == 0.0);
        assertThat(checks).allMatch(check -> check.getLog().contains("interrupted"));
    }

    private static class TestChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, Consumer<String> externalLogger) {
            if (userPrompt.equals("What is the answer?")) {
                return new StructuredResponse("42", List.of(), null, 100, 200, 1000);
            }
            if (userPrompt.equals("Who are you?")) {
                return new StructuredResponse("HAL9000", List.of(), null, 100, 200, 1000);
            }
            return new StructuredResponse("Unexpected input", List.of(), null, 100, 200, 1000);
        }
    }

    private static class TestExternalEvaluator implements ExternalEvaluator {

        @Override
        public double evaluateSemantic(String referenceAnswer, String actualAnswer, Consumer<String> logger) {
            if (referenceAnswer.equals(actualAnswer))
                return 1.0;
            else
                return 0.0;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getModelName() {
            return "test-evaluator";
        }

        @Override
        public String getEndpoint() {
            return "http://test-evaluator";
        }
    }

    private static class UnavailableExternalEvaluator implements ExternalEvaluator {

        @Override
        public double evaluateSemantic(String referenceAnswer, String actualAnswer, Consumer<String> logger) {
            throw new ExternalEvaluatorException("Semantic evaluator is not configured");
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String getModelName() {
            return "missing-evaluator";
        }

        @Override
        public String getEndpoint() {
            return null;
        }
    }

    private static class EchoChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, Consumer<String> externalLogger) {
            String answer = userPrompt.replace("Question ", "Answer ");
            return new StructuredResponse(answer, List.of(), null, 10, 10, 10);
        }
    }

    private static class FailingChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, Consumer<String> externalLogger) {
            if ("fail".equals(userPrompt)) {
                throw new RuntimeException("simulated failure");
            }
            return new StructuredResponse("ok", List.of(), null, 10, 10, 10);
        }
    }

    private static class EmptyChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, Consumer<String> externalLogger) {
            return new StructuredResponse("", List.of(), null, 10, 10, 10);
        }
    }

    private static class SlowChat implements Chat {

        private static final CountDownLatch STARTED = new CountDownLatch(1);

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, Consumer<String> externalLogger) {
            STARTED.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StructuredResponse("ok", List.of(), null, 10, 10, 10);
        }
    }
}
