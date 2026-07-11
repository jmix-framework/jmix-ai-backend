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
            You evaluate whether an actual answer correctly answers the user's question. The
            reference answer is factual guidance, not a template whose wording or length must be
            reproduced. Treat factual claims explicitly stated in the reference as authoritative:
            do not reject the same mechanism in the actual answer based on unsupported assumptions
            about framework internals. Alternative correct mechanisms are still acceptable. Do not
            penalize an additional factual detail solely because the reference omits it; penalize
            it only when it contradicts the reference or is unambiguously false. If uncertain,
            ignore that detail instead of guessing.

            FIRST decide the reference type:
            - REFUSAL: the reference declines to help, states the topic is out of scope, or redirects
              the user to the allowed scope (e.g. refuses a cooking or off-topic question, or declines
              questions about an unsupported product version).
            - CONTENT: the reference conveys substantive information answering the question.

            If the reference is a REFUSAL, the score MUST be exactly 1.0 or exactly 0.0 — never an
            intermediate value. Judge ONLY by intent:
            - Output exactly 1.0 if the actual answer declines or does not provide the requested
              out-of-scope content. A short, generic decline (e.g. "I can only help with Jmix/Java")
              is a full decline: its phrasing, length, whether it explicitly names the off-topic
              subject, and which alternatives it suggests MUST NOT lower the score.
            - Output exactly 0.0 only if the actual answer actually provides the requested
              out-of-scope content.
            For a REFUSAL reference always set "languageMatch" to true — language is irrelevant to a decline.

            If the reference is CONTENT, judge only what the user's question asks:
            - Do not penalize omission of examples, background, optional configuration or follow-up
              advice from the reference when the question did not request it.
            - Accept a shorter answer and alternative correct wording or API when it fully answers
              the question. Exact identifiers and signatures matter when the question asks for them.
            - Penalize missing required facts, contradictions, invented APIs and irrelevant content.
            - Use only these scores: 1.0 = fully correct; 0.9 = correct with a minor non-material
              issue; 0.7 = partially correct or missing a required fact; 0.3 = mostly incorrect;
              0.0 = incorrect, empty, or an inappropriate refusal.
            For a CONTENT reference, if the actual answer is not in the same language as the user's
            question, apply a strong penalty.

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
    public double evaluateSemantic(String question, String referenceAnswer, String actualAnswer,
                                   @Nullable Consumer<String> logger) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("User question:\n" + question
                            + "\n\nReference answer:\n" + referenceAnswer
                            + "\n\nActual answer:\n" + actualAnswer)
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
        double score = nearestContentScore(result.score());
        if (!result.languageMatch()) {
            return Math.min(score, LANGUAGE_MISMATCH_MAX_SCORE);
        }
        return score;
    }

    private static double nearestContentScore(double score) {
        if (score >= 0.95) {
            return 1.0;
        }
        if (score >= 0.8) {
            return 0.9;
        }
        if (score >= 0.5) {
            return 0.7;
        }
        return score >= 0.15 ? 0.3 : 0.0;
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
