package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AuthenticatedAsAdmin.class)
public class CheckRunnerTest {

    @Autowired
    DataManager dataManager;
    @Autowired
    DataSource dataSource;
    @Autowired
    SystemAuthenticator systemAuthenticator;

    CheckDef checkDef1;
    CheckDef checkDef2;
    final List<CheckRunner> runners = new ArrayList<>();

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
        runners.forEach(CheckRunner::shutdown);
        clearTables();
    }

    private void clearTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("delete from CHECK_");
        jdbc.execute("delete from CHECK_RUN");
        jdbc.execute("delete from CHECK_DEF");
    }

    @Test
    void runChecks_savesCompletedRun() {
        CheckRunner checkRunner = runner(new TestChat(), new TestExternalEvaluator(), 4);

        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("some parameters");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).isNotEmpty();

        Check check1 = checks.stream().filter(c -> c.getCheckDef().equals(checkDef1)).findFirst().orElseThrow();
        assertThat(check1.getCheckRun()).isEqualTo(checkRun);
        assertThat(check1.getCategory()).isEqualTo(checkDef1.getCategory());
        assertThat(check1.getScore()).isEqualTo(1.0);

        Check check2 = checks.stream().filter(c -> c.getCheckDef().equals(checkDef2)).findFirst().orElseThrow();
        assertThat(check2.getCheckRun()).isEqualTo(checkRun);
        assertThat(check2.getCategory()).isEqualTo(checkDef2.getCategory());
        assertThat(check2.getScore()).isEqualTo(0.0);

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getScore()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(updatedCheckRun.getEvaluatorConfig())
                .isEqualTo("semantic-v3|model=test-judge|temperature=0.0");
        assertThat(updatedCheckRun.getPassThreshold()).isEqualTo(0.8);
        assertThat(updatedCheckRun.getDefinitionFingerprint())
                .isEqualTo(CheckFingerprints.forDefinitions(List.of(checkDef1, checkDef2)));
    }

    @Test
    void runChecks_savesAllDefinitions() {
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

        CheckRunner checkRunner = runner(new EchoChat(), new TestExternalEvaluator(), 4);
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).hasSize(20);

        CheckRun updatedCheckRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(updatedCheckRun.getScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void runChecks_whenOneCheckFails_deletesRunAndPersistsNoChecks() {
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

        CheckRunner checkRunner = runner(new FailingChat(), new TestExternalEvaluator(), 4);
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        assertThatThrownBy(() -> checkRunner.runChecks(Id.of(checkRun)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated failure");

        assertThat(dataManager.load(Check.class).all().list()).isEmpty();
        assertThat(dataManager.load(CheckRun.class).all().list()).isEmpty();
    }

    @Test
    void runChecks_withoutActiveDefinitions_deletesRun() {
        clearTables();
        CheckRunner checkRunner = runner(new EchoChat(), new TestExternalEvaluator(), 2);
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        assertThatThrownBy(() -> checkRunner.runChecks(Id.of(checkRun)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No active check definitions for v2");

        assertThat(dataManager.load(Check.class).all().list()).isEmpty();
        assertThat(dataManager.load(CheckRun.class).all().list()).isEmpty();
    }

    @Test
    void runChecks_usesSharedAndMatchingVersionDefinitionsOnly() {
        clearTables();
        CheckDef shared = checkDef("shared", null);
        CheckDef v2 = checkDef("v2", JmixVersion.V2);
        CheckDef v3 = checkDef("v3", JmixVersion.V3);
        dataManager.save(shared, v2, v3);

        CheckRunner checkRunner = runner(new EchoChat(), new TestExternalEvaluator(), 2);
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setJmixVersion(JmixVersion.V2);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        assertThat(dataManager.load(Check.class).all().list())
                .extracting(Check::getQuestion)
                .containsExactlyInAnyOrder("shared", "v2")
                .doesNotContain("v3");
        CheckRun completedRun = dataManager.load(Id.of(checkRun)).one();
        assertThat(completedRun.getDefinitionFingerprint())
                .isEqualTo(CheckFingerprints.forDefinitions(List.of(shared, v2)));
        assertThat(completedRun.getConfigLabel()).isNull();
    }

    @Test
    void runChecks_whenCancelled_cancelsQueuedWorkAndDeletesRun() throws Exception {
        clearTables();
        for (int i = 0; i < 5; i++) {
            dataManager.save(checkDef("question-" + i, null));
        }

        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        Chat blockingChat = (question, parameters, conversationId, version, logger) -> {
            requestCount.incrementAndGet();
            firstRequestStarted.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("unreachable");
            } catch (InterruptedException e) {
                workerInterrupted.countDown();
                while (releaseWorker.getCount() > 0) {
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException ignored) {
                        // Keep the first worker occupied until the queued futures have been cancelled.
                    }
                }
                throw new IllegalStateException("worker interrupted", e);
            }
        };

        CheckRunner checkRunner = runner(blockingChat, new TestExternalEvaluator(), 1);
        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("unused");
        dataManager.save(checkRun);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> systemAuthenticator.runWithUser("admin", () -> {
            try {
                checkRunner.runChecks(Id.of(checkRun));
            } catch (Throwable e) {
                failure.set(e);
            }
        }), "check-run-caller-test");

        try {
            caller.start();
            assertThat(firstRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();

            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(caller.isAlive()).isFalse();
            assertThat(workerInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(requestCount).hasValue(1);
            assertThat(failure.get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Check run was cancelled");
            assertThat(dataManager.load(Check.class).all().list()).isEmpty();
            assertThat(dataManager.load(CheckRun.class).all().list()).isEmpty();
        } finally {
            releaseWorker.countDown();
        }
    }

    private CheckDef checkDef(String question, JmixVersion version) {
        CheckDef checkDef = dataManager.create(CheckDef.class);
        checkDef.setCategory("version");
        checkDef.setQuestion(question);
        checkDef.setAnswer(question);
        checkDef.setActive(true);
        checkDef.setJmixVersion(version);
        return checkDef;
    }

    private CheckRunner runner(Chat chat, ExternalEvaluator evaluator, int parallelism) {
        CheckRunner runner = new CheckRunner(
                dataManager, chat, evaluator, parallelism, 0.8);
        runners.add(runner);
        return runner;
    }

    private static class TestChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, JmixVersion jmixVersion, Consumer<String> externalLogger) {
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
        public String configurationSnapshot() {
            return "semantic-v3|model=test-judge|temperature=0.0";
        }

        @Override
        public double evaluateSemantic(String question, String referenceAnswer, String actualAnswer,
                                       Consumer<String> logger) {
            if (referenceAnswer.equals(actualAnswer))
                return 1.0;
            else
                return 0.0;
        }
    }

    private static class EchoChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, JmixVersion jmixVersion, Consumer<String> externalLogger) {
            String answer = userPrompt.replace("Question ", "Answer ");
            return new StructuredResponse(answer, List.of(), null, 10, 10, 10);
        }
    }

    private static class FailingChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, JmixVersion jmixVersion, Consumer<String> externalLogger) {
            if ("fail".equals(userPrompt)) {
                throw new RuntimeException("simulated failure");
            }
            return new StructuredResponse("ok", List.of(), null, 10, 10, 10);
        }
    }
}
