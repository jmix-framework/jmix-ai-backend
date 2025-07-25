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
import org.springframework.scripting.ScriptEvaluator;
import org.springframework.test.context.ActiveProfiles;
import test_support.AuthenticatedAsAdmin;

import javax.sql.DataSource;
import java.util.List;
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
    @Autowired
    ScriptEvaluator scriptEvaluator;

    CheckDef checkDef1;
    CheckDef checkDef2;

    @BeforeEach
    void setUp() {
        checkDef1 = dataManager.create(CheckDef.class);
        checkDef1.setCategory("basic");
        checkDef1.setQuestion("What is the answer?");
        checkDef1.setAnswer("42");
        checkDef1.setScript("actualAnswer == referenceAnswer ? 1.0 : 0.0");
        checkDef1.setActive(true);
        checkDef1.setRouge(true);
        checkDef1.setBert(true);
        dataManager.save(checkDef1);

        checkDef2 = dataManager.create(CheckDef.class);
        checkDef2.setCategory("basic");
        checkDef2.setQuestion("Who are you?");
        checkDef2.setAnswer("Jmix AI");
        checkDef2.setScript("actualAnswer == referenceAnswer ? 1.0 : 0.0");
        checkDef2.setActive(true);
        checkDef2.setRouge(true);
        checkDef2.setBert(true);
        dataManager.save(checkDef2);
    }

    @AfterEach
    void tearDown() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("delete from CHECK_");
        jdbc.execute("delete from CHECK_RUN");
        jdbc.execute("delete from CHECK_DEF");
    }

    @Test
    void test() {
        CheckRunner checkRunner = new CheckRunner(dataManager, new TestChat(), scriptEvaluator, new TestExternalEvaluator());

        CheckRun checkRun = dataManager.create(CheckRun.class);
        checkRun.setParameters("some parameters");
        dataManager.save(checkRun);

        checkRunner.runChecks(Id.of(checkRun));

        List<Check> checks = dataManager.load(Check.class).all().list();
        assertThat(checks).size().isEqualTo(2);

        Check check1 = checks.stream().filter(c -> c.getCheckDef().equals(checkDef1)).findFirst().orElseThrow();
        assertThat(check1.getCheckRun()).isEqualTo(checkRun);
        assertThat(check1.getCategory()).isEqualTo(checkDef1.getCategory());
        assertThat(check1.getScriptScore()).isEqualTo(1.0);
        assertThat(check1.getRougeScore()).isEqualTo(1.0);
        assertThat(check1.getBertScore()).isEqualTo(1.0);

        Check check2 = checks.stream().filter(c -> c.getCheckDef().equals(checkDef2)).findFirst().orElseThrow();
        assertThat(check2.getCheckRun()).isEqualTo(checkRun);
        assertThat(check2.getCategory()).isEqualTo(checkDef2.getCategory());
        assertThat(check2.getScriptScore()).isEqualTo(0.0);
        assertThat(check2.getRougeScore()).isEqualTo(0.0);
        assertThat(check2.getBertScore()).isEqualTo(0.0);
    }

    private static class TestChat implements Chat {

        @Override
        public StructuredResponse requestStructured(String userPrompt, String parametersYaml, String conversationId, Consumer<String> externalLogger) {
            if (userPrompt.equals("What is the answer?")) {
                return new StructuredResponse("42", List.of(), null);
            }
            if (userPrompt.equals("Who are you?")) {
                return new StructuredResponse("HAL9000", List.of(), null);
            }
            return new StructuredResponse("Unexpected input", List.of(), null);
        }
    }

    private static class TestExternalEvaluator implements ExternalEvaluator {

        @Override
        public double evaluate(Type type, String referenceAnswer, String actualAnswer, Consumer<String> logger) {
            if (referenceAnswer.equals(actualAnswer))
                return 1.0;
            else
                return 0.0;
        }
    }
}
