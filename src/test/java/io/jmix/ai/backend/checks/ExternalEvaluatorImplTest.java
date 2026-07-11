package io.jmix.ai.backend.checks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalEvaluatorImplTest {

    @Test
    void parseEvaluationResponse_ParsesAndClampsScore() throws Exception {
        ExternalEvaluatorImpl.EvaluationResult result = ExternalEvaluatorImpl.parseEvaluationResponse("""
                {"score": 1.3, "verdict": "PASS", "rationale": "close enough", "languageMatch": true}
                """);

        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.rationale()).isEqualTo("close enough");
        assertThat(result.languageMatch()).isTrue();
    }

    @Test
    void parseEvaluationResponse_ThrowsOnMalformedJson() {
        assertThatThrownBy(() -> ExternalEvaluatorImpl.parseEvaluationResponse("not-json"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void parseEvaluationResponse_ThrowsWhenScoreMissing() {
        assertThatThrownBy(() -> ExternalEvaluatorImpl.parseEvaluationResponse("{\"verdict\":\"FAIL\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
    }

    @Test
    void normalizeScore_AppliesStrongPenaltyOnLanguageMismatch() {
        ExternalEvaluatorImpl.EvaluationResult result =
                new ExternalEvaluatorImpl.EvaluationResult(0.93, "PASS", "wrong language", false);

        double normalized = ExternalEvaluatorImpl.normalizeScore(result);

        assertThat(normalized).isEqualTo(0.2);
    }

    @ParameterizedTest
    @CsvSource({
            "0.0,      0.0",
            "0.149999, 0.0",
            "0.15,     0.3",
            "0.499999, 0.3",
            "0.5,      0.7",
            "0.799999, 0.7",
            "0.8,      0.9",
            "0.949999, 0.9",
            "0.95,     1.0",
            "1.0,      1.0"
    })
    void normalizeScore_QuantizesAtEveryContentScoreBoundary(double score, double expected) {
        ExternalEvaluatorImpl.EvaluationResult result =
                new ExternalEvaluatorImpl.EvaluationResult(score, "PARTIAL", "ok", true);

        double normalized = ExternalEvaluatorImpl.normalizeScore(result);

        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    void evaluateSemantic_ReturnsZeroWhenModelFails() {
        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenThrow(new RuntimeException("boom"));
        ExternalEvaluatorImpl evaluator = new ExternalEvaluatorImpl(chatModel);

        double score = evaluator.evaluateSemantic("question", "ref", "actual", null);

        assertThat(score).isEqualTo(0.0);
        assertThat(promptCaptor.getValue().getSystemMessage().getText())
                .contains("Treat factual claims explicitly stated in the reference as authoritative")
                .contains("Alternative correct mechanisms are still acceptable")
                .contains("If uncertain,", "ignore that detail instead of guessing");
        assertThat(promptCaptor.getValue().getUserMessage().getText())
                .contains("User question:\nquestion", "Reference answer:\nref", "Actual answer:\nactual");
    }
}
