package io.jmix.ai.backend.checks;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void normalizeScore_LeavesScoreWhenLanguageMatches() {
        ExternalEvaluatorImpl.EvaluationResult result =
                new ExternalEvaluatorImpl.EvaluationResult(0.74, "PARTIAL", "ok", true);

        double normalized = ExternalEvaluatorImpl.normalizeScore(result);

        assertThat(normalized).isEqualTo(0.74);
    }

    @Test
    void evaluateSemantic_ReturnsZeroWhenModelFails() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));
        ExternalEvaluatorImpl evaluator = new ExternalEvaluatorImpl(chatModel);

        double score = evaluator.evaluateSemantic("ref", "actual", null);

        assertThat(score).isEqualTo(0.0);
    }
}
