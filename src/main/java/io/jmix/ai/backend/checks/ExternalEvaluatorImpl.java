package io.jmix.ai.backend.checks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.function.Consumer;

import static org.apache.commons.lang3.StringUtils.abbreviate;

@Component
public class ExternalEvaluatorImpl implements ExternalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ExternalEvaluatorImpl.class);

    private final RestTemplate restTemplate;
    private final String rougeUrl;
    private final String bertUrl;

    public ExternalEvaluatorImpl(
            @Value("${answer-checks.rouge.url:http://localhost:8001/rouge}") String rougeUrl,
            @Value("${answer-checks.bert.url:http://localhost:8001/bert}") String bertUrl
    ) {
        this.restTemplate = new RestTemplate();
        this.rougeUrl = rougeUrl;
        this.bertUrl = bertUrl;
    }

    @Override
    public double evaluate(Type type, String referenceAnswer, String actualAnswer, @Nullable Consumer<String> logger) {
        return switch (type) {
            case ROUGE -> evaluateRouge(referenceAnswer, actualAnswer, logger);
            case BERT -> evaluateBert(referenceAnswer, actualAnswer, logger);
        };
    }

    private double evaluateRouge(String referenceAnswer, String actualAnswer, @Nullable Consumer<String> logger) {
        log.info("Sending request to {}: referenceAnswer={}, actualAnswer={}", rougeUrl, abbreviate(referenceAnswer, 100), abbreviate(actualAnswer, 100));
        Map<String, Object> request = Map.of(
                "reference", referenceAnswer,
                "actual", actualAnswer
        );
        RougeScoreRec response;
        try {
            response = restTemplate.postForObject(rougeUrl, request, RougeScoreRec.class);
        } catch (RestClientException e) {
            log.error("Request failed", e);
            if (logger != null) {
                logger.accept("Request failed: " + e.getMessage());
            }
            return 0;
        }
        log.info("Response: {}", response);
        if (logger != null) {
            logger.accept("ROUGE response: " + response);
        }
        if (response != null) {
            return response.rouge1().f1();
        }
        return 0;
    }

    private double evaluateBert(String referenceAnswer, String actualAnswer, @Nullable Consumer<String> logger) {
        log.info("Sending request to {}: referenceAnswer={}, actualAnswer={}", bertUrl, abbreviate(referenceAnswer, 100), abbreviate(actualAnswer, 100));
        Map<String, Object> request = Map.of(
                "reference", referenceAnswer,
                "actual", actualAnswer
        );
        ScoreRec response;
        try {
            response = restTemplate.postForObject(bertUrl, request, ScoreRec.class);
        } catch (RestClientException e) {
            log.error("Request failed", e);
            if (logger != null) {
                logger.accept("Request failed: " + e.getMessage());
            }
            return 0;
        }
        log.info("Response: {}", response);
        if (logger != null) {
            logger.accept("BERT response: " + response);
        }
        if (response != null) {
            return response.f1();
        }
        return 0;
    }

    private record ScoreRec(double precision, double recall, double f1) {}

    private record RougeScoreRec(ScoreRec rouge1, ScoreRec rouge2, ScoreRec rougeL) {}
}
