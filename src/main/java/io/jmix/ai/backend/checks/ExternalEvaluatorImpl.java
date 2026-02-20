package io.jmix.ai.backend.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExternalEvaluatorImpl implements ExternalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ExternalEvaluatorImpl.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final double LANGUAGE_MISMATCH_MAX_SCORE = 0.2;

    private static final String SYSTEM_PROMPT = """
            You evaluate semantic similarity between a reference answer and an actual answer.
            Score in range [0,1] using this rubric:
            - Semantic correctness vs reference: 60%%
            - Completeness of key points: 30%%
            - Penalize contradictions/hallucinations/irrelevant content: 10%%
            - If actual answer is not in the same language as reference, apply strong penalty.

            Return ONLY valid JSON without markdown fences:
            {
              "score": <number 0..1>,
              "verdict": "PASS" | "PARTIAL" | "FAIL",
              "rationale": "short explanation",
              "languageMatch": true | false
            }
            """;

    private final ChatModel chatModel;

    @Autowired
    public ExternalEvaluatorImpl(
            @Value("${answer-checks.model:gpt-5-mini}") String model,
            @Value("${answer-checks.temperature:0}") double temperature,
            @Value("${spring.ai.openai.api-key:}") String configuredApiKey
    ) {
        String apiKey = StringUtils.defaultIfBlank(configuredApiKey, System.getenv("OPENAI_API_KEY"));
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalStateException("OPENAI API key is not set (spring.ai.openai.api-key or OPENAI_API_KEY)");
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    ExternalEvaluatorImpl(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public double evaluateSemantic(String referenceAnswer, String actualAnswer, @Nullable Consumer<String> logger) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("Reference answer:\n" + referenceAnswer + "\n\nActual answer:\n" + actualAnswer)
            ));

            ChatResponse response = chatModel.call(prompt);
            String content = getContent(response);
            if (content == null) {
                throw new IllegalArgumentException("Empty evaluator response");
            }

            EvaluationResult result = parseEvaluationResponse(content);

            double normalizedScore = normalizeScore(result);
            if (logger != null) {
                logger.accept("Semantic evaluator response: " + result + ", normalizedScore=" + normalizedScore);
            }
            return normalizedScore;
        } catch (Exception e) {
            log.error("Failed to evaluate semantic score", e);
            if (logger != null) {
                logger.accept("Semantic evaluator failed: " + e.getMessage());
            }
            return 0.0;
        }
    }

    static EvaluationResult parseEvaluationResponse(String text) throws Exception {
        String json = extractJsonObject(text);
        JsonNode root = OBJECT_MAPPER.readTree(json);

        JsonNode scoreNode = root.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            throw new IllegalArgumentException("Missing numeric 'score' in evaluator response");
        }

        double score = clampScore(scoreNode.asDouble());
        String verdict = root.path("verdict").asText("");
        String rationale = root.path("rationale").asText("");
        boolean languageMatch = root.path("languageMatch").asBoolean(true);

        return new EvaluationResult(score, verdict, rationale, languageMatch);
    }

    static double normalizeScore(EvaluationResult result) {
        if (!result.languageMatch()) {
            return Math.min(result.score(), LANGUAGE_MISMATCH_MAX_SCORE);
        }
        return result.score();
    }

    private static String extractJsonObject(String text) {
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException("Empty evaluator response");
        }

        Matcher matcher = JSON_OBJECT_PATTERN.matcher(text.trim());
        if (!matcher.find()) {
            throw new IllegalArgumentException("No JSON object found in evaluator response");
        }
        return matcher.group();
    }

    private static double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static @Nullable String getContent(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    record EvaluationResult(double score, String verdict, String rationale, boolean languageMatch) {
    }
}
